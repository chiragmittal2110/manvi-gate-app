// Runs inside the Manvi Gate app only. When the kiosk calls window.print(), this draws the slip
// (#printarea) as a picture and hands it to the Android app, which prints it on the Bluetooth printer.
(function () {
  if (!window.AndroidPrinter || window.__bridgeInstalled) return;
  window.__bridgeInstalled = true;

  var W = 576;      // dots across an 80 mm slip
  var K = 2;        // the slip layout is written in CSS pixels; 1 CSS px = 2 dots
  var MONO = '"Courier New", Courier, monospace';
  var HIN = ', "Noto Sans Devanagari", sans-serif';

  function font(px, bold) { return (bold ? "bold " : "") + Math.round(px * K) + "px " + MONO + HIN; }

  function wrap(ctx, text, maxW) {
    text = String(text == null ? "" : text);
    var words = text.split(/\s+/), lines = [], cur = "";
    for (var i = 0; i < words.length; i++) {
      var w = words[i], test = cur ? cur + " " + w : w;
      if (ctx.measureText(test).width <= maxW) { cur = test; continue; }
      if (cur) { lines.push(cur); cur = ""; }
      while (ctx.measureText(w).width > maxW && w.length > 1) {   // break a very long word
        var k = w.length - 1;
        while (k > 1 && ctx.measureText(w.slice(0, k)).width > maxW) k--;
        lines.push(w.slice(0, k)); w = w.slice(k);
      }
      cur = w;
    }
    lines.push(cur);
    return lines;
  }

  function draw(ctx, pc) {
    var y = Math.round(2 * 3.78 * K);   // 2 mm top padding
    ctx.fillStyle = "#000"; ctx.strokeStyle = "#000"; ctx.textBaseline = "alphabetic";
    var kids = pc.children;
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i], cls = el.className || "", tag = el.tagName;
      var lh;
      if (cls.indexOf("ph") >= 0 && tag === "DIV" && cls.split(" ").indexOf("ph") >= 0) {
        ctx.font = font(16, true); ctx.textAlign = "center"; lh = 20 * K;
        wrap(ctx, el.textContent, W).forEach(function (t) { y += lh; ctx.fillText(t, W / 2, y - 5 * K); });
      } else if (cls.split(" ").indexOf("pt") >= 0) {
        ctx.font = font(11, false); ctx.textAlign = "center"; lh = 14 * K;
        wrap(ctx, el.textContent, W).forEach(function (t) { y += lh; ctx.fillText(t, W / 2, y - 3 * K); });
        y += 2 * 3.78 * K;
      } else if (cls.split(" ").indexOf("pl") >= 0) {
        y += 2 * 3.78 * K;
        ctx.setLineDash([6, 5]); ctx.lineWidth = 2;
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke(); ctx.setLineDash([]);
        y += 2 * 3.78 * K;
      } else if (cls.split(" ").indexOf("prow") >= 0) {
        var big = cls.split(" ").indexOf("big2") >= 0;
        var size = big ? 16 : 11.5; lh = (big ? 20 : 14.5) * K;
        var lab = el.children[0] ? el.children[0].textContent : "";
        var val = el.children[1] ? el.children[1].textContent : "";
        ctx.font = font(size, false); var lw = ctx.measureText(lab).width;
        ctx.font = font(size, true);
        var lines = wrap(ctx, val, Math.max(80, W - lw - 12 * K));
        ctx.textAlign = "left"; ctx.font = font(size, false); ctx.fillText(lab, 0, y + lh - 4 * K);
        ctx.textAlign = "right"; ctx.font = font(size, true);
        lines.forEach(function (t, n) { ctx.fillText(t, W, y + lh * (n + 1) - 4 * K); });
        y += lh * lines.length;
      } else if (tag === "TABLE") {
        var c1 = Math.round(W * 0.36), qtyW = Math.round(W * 0.14);
        var rows = el.querySelectorAll("tr");
        for (var r = 0; r < rows.length; r++) {
          var row = rows[r], cells = row.children, isHead = !!row.querySelector("th");
          var sz = isHead ? 11 : 11.5; lh = (isHead ? 14 : 14.5) * K;
          if (cells.length === 1) {   // a full-width note row such as EXCESS
            ctx.font = font(11, true); ctx.textAlign = "left";
            wrap(ctx, cells[0].textContent, W).forEach(function (t) { y += lh; ctx.fillText(t, 0, y - 4 * K); });
            continue;
          }
          ctx.font = font(sz, isHead);
          var a = wrap(ctx, cells[0].textContent, c1 - 8 * K);
          var b = wrap(ctx, cells[1].textContent, W - c1 - qtyW - 8 * K);
          var n = Math.max(a.length, b.length);
          ctx.textAlign = "left";
          a.forEach(function (t, k) { ctx.fillText(t, 0, y + lh * (k + 1) - 4 * K); });
          b.forEach(function (t, k) { ctx.fillText(t, c1, y + lh * (k + 1) - 4 * K); });
          ctx.textAlign = "right"; ctx.fillText(cells[2] ? cells[2].textContent : "", W, y + lh - 4 * K);
          y += lh * n;
          if (isHead) { ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke(); y += 2 * K; }
        }
      } else if (cls.split(" ").indexOf("sig") >= 0) {
        y += 9 * 3.78 * K;
        ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke();
        ctx.font = font(10, false); ctx.textAlign = "center"; lh = 13 * K;
        y += 1 * 3.78 * K;
        wrap(ctx, el.textContent, W).forEach(function (t) { y += lh; ctx.fillText(t, W / 2, y - 3 * K); });
      } else if (cls.split(" ").indexOf("pf") >= 0) {
        y += 3 * 3.78 * K;
        ctx.font = font(10, false); ctx.textAlign = "center"; lh = 13 * K;
        wrap(ctx, el.textContent, W).forEach(function (t) { y += lh; ctx.fillText(t, W / 2, y - 3 * K); });
      } else if (el.textContent) {  // anything unexpected: print it plainly so nothing is lost
        ctx.font = font(11.5, false); ctx.textAlign = "left"; lh = 14.5 * K;
        wrap(ctx, el.textContent, W).forEach(function (t) { y += lh; ctx.fillText(t, 0, y - 4 * K); });
      }
    }
    return y + 12 * K;
  }

  function slipToPng() {
    var area = document.getElementById("printarea");
    var pc = area && area.querySelector(".pc");
    if (!pc) return null;
    var tall = document.createElement("canvas"); tall.width = W; tall.height = 4000;
    var tctx = tall.getContext("2d");
    tctx.fillStyle = "#fff"; tctx.fillRect(0, 0, W, 4000);
    var h = Math.min(4000, Math.ceil(draw(tctx, pc)));
    var out = document.createElement("canvas"); out.width = W; out.height = h;
    var octx = out.getContext("2d");
    octx.fillStyle = "#fff"; octx.fillRect(0, 0, W, h);
    octx.drawImage(tall, 0, 0, W, h, 0, 0, W, h);
    return out.toDataURL("image/png");
  }

  window.__slipToPng = slipToPng;   // handy for testing
  window.print = function () {
    try {
      var png = slipToPng();
      if (!png) return;
      window.AndroidPrinter.printPng(png.split(",")[1]);
    } catch (e) { console.error("print bridge", e); }
  };
})();
