/* Realce de cores dos logs (regras estilo «Highlighting» do Grep Console, simplificado).
 * UMD: corre no webview (script) e nos testes (require). Uma regra:
 * { enabled, name, pattern, regex, caseSensitive, wholeLine, blockEnd, background, foreground, bold }
 * blockEnd preenchido => pinta um BLOCO: da linha que casa até à que casa com blockEnd (inclusive). */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.PemHighlight = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  const DEFAULT_RULES = () => [
    { enabled: true, name: 'WebLogic <Error> (bloco)', pattern: '<Error>', mode: 'contains', regex: false, caseSensitive: false, wholeLine: true, blockEnd: '>\\s*$', background: '#B71C1C', foreground: '#FFFFFF', bold: false },
    { enabled: true, name: 'Erro', pattern: '\\b(FATAL|SEVERE|ERROR)\\b|BUILD FAILURE|Caused by:', mode: 'regex', regex: true, caseSensitive: false, wholeLine: true, blockEnd: '', background: '#7F1D1D', foreground: '#FFFFFF', bold: false },
    { enabled: true, name: 'Aviso', pattern: '<Warning>|\\bWARN(ING)?\\b', mode: 'regex', regex: true, caseSensitive: false, wholeLine: true, blockEnd: '', background: '#7A5C00', foreground: '#FFF3C4', bold: false },
    { enabled: true, name: 'Sucesso', pattern: 'BUILD SUCCESS|Server state changed to RUNNING|BEA-149074', mode: 'regex', regex: true, caseSensitive: false, wholeLine: true, blockEnd: '', background: '#1B5E20', foreground: '#FFFFFF', bold: false },
    { enabled: true, name: 'Estado do servidor', pattern: 'Server state changed to \\w+', mode: 'regex', regex: true, caseSensitive: false, wholeLine: false, blockEnd: '', background: '#0D47A1', foreground: '#FFFFFF', bold: true },
  ];

  const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  /** contains | starts | ends | equals | regex (regras antigas só têm o booleano regex) */
  const modeOf = (r) => r.mode || (r.regex === false ? 'contains' : 'regex');
  /** Fora de regex, '*' é um curinga; o modo põe as âncoras (prefixo, sufixo, igual). */
  function toSource(r) {
    const m = modeOf(r);
    if (m === 'regex') return r.pattern;
    const body = r.pattern.split('*').map(escapeRe).join('.*');
    return m === 'starts' ? '^' + body : m === 'ends' ? body + '$' : m === 'equals' ? '^' + body + '$' : body;
  }

  function compile(rules) {
    const out = [];
    for (const r of rules || []) {
      if (!r || r.enabled === false || !r.pattern) continue;
      try {
        const flags = r.caseSensitive ? '' : 'i';
        const src = toSource(r);
        out.push({
          r,
          p: new RegExp(src, flags),
          pg: new RegExp(src, flags + 'g'),
          end: r.blockEnd ? new RegExp(r.blockEnd, flags) : null,
          style: { foreground: r.foreground || '', background: r.background || '', bold: !!r.bold },
        });
      } catch (e) { /* regex inválida: ignora a regra */ }
    }
    return out;
  }

  /** Motor com estado (blocos): um por canal/consola. */
  class Engine {
    constructor(rules) { this.setRules(rules); }
    setRules(rules) { this.compiled = compile(rules); this.block = null; }
    reset() { this.block = null; }
    /** Devolve [{text, style|null}] (a linha sem o terminador). */
    segments(line) {
      if (this.block) {
        const open = this.block;
        if (open.end.test(line)) this.block = null;
        return [{ text: line, style: open.style, whole: true }];
      }
      for (const c of this.compiled) {
        if (!c.p.test(line)) continue;
        if (c.end) { if (!c.end.test(line)) this.block = c; return [{ text: line, style: c.style, whole: true }]; }
        if (c.r.wholeLine !== false) return [{ text: line, style: c.style, whole: true }];
      }
      const taken = [];
      for (const c of this.compiled) {
        if (c.end || c.r.wholeLine !== false) continue;
        c.pg.lastIndex = 0;
        let m;
        while ((m = c.pg.exec(line)) !== null) {
          if (m[0].length === 0) { c.pg.lastIndex++; continue; }
          const s = m.index, e = s + m[0].length;
          if (!taken.some((t) => s < t.e && e > t.s)) taken.push({ s, e, style: c.style });
        }
      }
      if (!taken.length) return [{ text: line, style: null }];
      taken.sort((a, b) => a.s - b.s);
      const out = [];
      let pos = 0;
      for (const t of taken) {
        if (t.s > pos) out.push({ text: line.slice(pos, t.s), style: null });
        out.push({ text: line.slice(t.s, t.e), style: t.style });
        pos = t.e;
      }
      if (pos < line.length) out.push({ text: line.slice(pos), style: null });
      return out;
    }
  }

  return { Engine, DEFAULT_RULES, toSource };
});
