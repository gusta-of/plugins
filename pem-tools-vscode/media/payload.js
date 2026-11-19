/* Deteta JSON e SOAP (Envelope) nas linhas dos logs, mesmo repartidos por várias linhas.
 * UMD: webview e testes. Uso: d = new PayloadDetector(); d.push(linha, ref) -> [{ ref, kind: 'json'|'xml', text }]
 * `ref` é a linha onde o payload COMEÇA (é aí que o botão aparece). */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.PemPayload = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  const MAX_LINES = 600;
  const MAX_CHARS = 400000;
  const ENV_OPEN = /<(\w[\w.-]*:)?Envelope\b/i;
  const ENV_CLOSE = /<\/(\w[\w.-]*:)?Envelope\s*>/i;

  function validJson(s) {
    try {
      const v = JSON.parse(s);
      if (Array.isArray(v)) return v.length > 0;
      return v !== null && typeof v === 'object' && Object.keys(v).length > 0;
    } catch (e) { return false; }
  }

  /** Percorre [text] a partir de [from] (que é { ou [) e devolve {end, depth, inStr, esc}: end = índice do fecho, ou -1. */
  function scan(text, from, st) {
    let depth = st.depth, inStr = st.inStr, esc = st.esc;
    for (let i = from; i < text.length; i++) {
      const c = text[i];
      if (inStr) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
      if (c === '"') inStr = true;
      else if (c === '{' || c === '[') depth++;
      else if (c === '}' || c === ']') { depth--; if (depth === 0) return { end: i, depth, inStr, esc }; if (depth < 0) return { end: -1, depth: -1, inStr, esc }; }
    }
    return { end: -1, depth, inStr, esc };
  }

  class PayloadDetector {
    constructor() { this.mode = null; }

    reset() { this.mode = null; }

    push(line, ref) {
      const m = this.mode;
      if (m) return m.kind === 'xml' ? this.continueXml(line) : this.continueJson(line);
      const out = [];
      const x = ENV_OPEN.exec(line);
      if (x) {
        const text = line.slice(x.index);
        const c = ENV_CLOSE.exec(text);
        if (c) out.push({ ref, kind: 'xml', text: text.slice(0, c.index + c[0].length) });
        else this.mode = { kind: 'xml', ref, lines: [text], chars: text.length };
        return out;
      }
      return this.startJson(line, ref);
    }

    continueXml(line) {
      const m = this.mode;
      m.lines.push(line); m.chars += line.length;
      const c = ENV_CLOSE.exec(line);
      if (c) {
        const lines = m.lines.slice(); lines[lines.length - 1] = line.slice(0, c.index + c[0].length);
        this.mode = null;
        return [{ ref: m.ref, kind: 'xml', text: lines.join('\n') }];
      }
      if (m.lines.length > MAX_LINES || m.chars > MAX_CHARS) this.mode = null;
      return [];
    }

    startJson(line, ref) {
      let tried = 0;
      for (let i = 0; i < line.length && tried < 6; i++) {
        const ch = line[i];
        if (ch !== '{' && ch !== '[') continue;
        tried++;
        const r = scan(line, i, { depth: 0, inStr: false, esc: false });
        if (r.end >= 0) {
          const sub = line.slice(i, r.end + 1);
          if (sub.length >= 4 && validJson(sub)) return [{ ref, kind: 'json', text: sub }];
          // pode ser um [INFO] ou {0}: tenta o candidato seguinte
          continue;
        }
        // não fecha nesta linha: só abre um bloco multi-linha se parecer o início de JSON (e não "[INFO]")
        const rest = line.slice(i + 1);
        const next = rest.trimStart()[0];
        const looksJson = ch === '{' ? (next === undefined || next === '"' || next === '}') : (next === undefined || next === '{' || next === '"' || next === '[' || /[\d-]/.test(next));
        if (looksJson) {
          this.mode = { kind: 'json', ref, lines: [line.slice(i)], chars: line.length - i, st: { depth: r.depth, inStr: r.inStr, esc: r.esc } };
        }
        return [];
      }
      return [];
    }

    continueJson(line) {
      const m = this.mode;
      m.lines.push(line); m.chars += line.length;
      const r = scan(line, 0, m.st);
      if (r.end >= 0) {
        const lines = m.lines.slice(); lines[lines.length - 1] = line.slice(0, r.end + 1);
        const text = lines.join('\n');
        this.mode = null;
        return validJson(text) ? [{ ref: m.ref, kind: 'json', text }] : [];
      }
      m.st = { depth: r.depth, inStr: r.inStr, esc: r.esc };
      if (r.depth < 0 || m.lines.length > MAX_LINES || m.chars > MAX_CHARS) this.mode = null;
      return [];
    }
  }

  function prettyJson(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
  }

  /** Indenta XML sem bibliotecas (aceita fragmentos e SOAP com prefixos). */
  function prettyXml(text) {
    const tokens = text.match(/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<[^>]+>|[^<]+/g) || [];
    const isOpen = (t) => /^<[^/!?]/.test(t) && !t.endsWith('/>');
    const isClose = (t) => t.startsWith('</');
    const out = [];
    let depth = 0;
    const pad = () => '  '.repeat(Math.max(depth, 0));
    for (let i = 0; i < tokens.length; i++) {
      const t = tokens[i];
      if (!t.startsWith('<')) { const s = t.trim(); if (s) out.push(pad() + s); continue; }
      if (isClose(t)) { depth--; out.push(pad() + t); continue; }
      if (isOpen(t)) {
        const n1 = tokens[i + 1], n2 = tokens[i + 2];
        if (n1 !== undefined && !n1.startsWith('<') && n2 !== undefined && isClose(n2)) { out.push(pad() + t + n1.trim() + n2); i += 2; continue; }
        if (n1 !== undefined && isClose(n1)) { out.push(pad() + t + n1); i += 1; continue; }
        out.push(pad() + t); depth++; continue;
      }
      out.push(pad() + t);
    }
    return out.join('\n');
  }

  const format = (kind, text) => (kind === 'json' ? prettyJson(text) : prettyXml(text));

  /**
   * Regiões colapsáveis do texto já indentado (JSON/XML formatados): para cada linha, o índice da última linha do bloco
   * (inclui a linha de fecho `}` / `]` / `</tag>` quando existe) ou -1 se a linha não abre nenhum bloco.
   */
  function foldRegions(lines) {
    const n = lines.length;
    const ends = new Array(n).fill(-1);
    const blank = lines.map((l) => !l.trim());
    const ind = lines.map((l) => l.length - l.trimStart().length);
    for (let i = 0; i < n; i++) {
      if (blank[i]) continue;
      let k = i;
      for (let j = i + 1; j < n && (blank[j] || ind[j] > ind[i]); j++) if (!blank[j]) k = j;
      if (k === i) continue;
      const c = k + 1 < n && !blank[k + 1] && ind[k + 1] === ind[i] && /^(\}|\]|<\/)/.test(lines[k + 1].trim());
      ends[i] = c ? k + 1 : k;
    }
    return ends;
  }

  /** Texto mostrado depois do «…» quando o bloco da linha [i] está recolhido (o fecho, ou a contagem de linhas). */
  function foldTail(lines, i, end) {
    const last = lines[end].trim();
    return end > i && /^(\}|\]|<\/)/.test(last) && lines[end].length - lines[end].trimStart().length === lines[i].length - lines[i].trimStart().length
      ? last : '(' + (end - i) + ' linhas)';
  }

  return { PayloadDetector, validJson, prettyJson, prettyXml, format, foldRegions, foldTail };
});
