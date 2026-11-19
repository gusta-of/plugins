/** Formatação de payloads (JSON e XML/SOAP) detetados nos logs. */

export function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** Indenta XML sem depender de bibliotecas (aceita fragmentos e SOAP com prefixos). */
export function prettyXml(text: string): string {
  const tokens = text.match(/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<[^>]+>|[^<]+/g) ?? [];
  const isOpen = (t: string) => /^<[^/!?]/.test(t) && !t.endsWith('/>');
  const isClose = (t: string) => t.startsWith('</');
  const out: string[] = [];
  let depth = 0;
  const pad = () => '  '.repeat(Math.max(depth, 0));
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (!t.startsWith('<')) {
      const s = t.trim();
      if (s) out.push(pad() + s);
      continue;
    }
    if (isClose(t)) { depth--; out.push(pad() + t); continue; }
    if (isOpen(t)) {
      // <a>texto</a> numa só linha
      const n1 = tokens[i + 1], n2 = tokens[i + 2];
      if (n1 !== undefined && !n1.startsWith('<') && n2 !== undefined && isClose(n2)) {
        out.push(pad() + t + n1.trim() + n2);
        i += 2;
        continue;
      }
      // <a></a> vazio
      if (n1 !== undefined && isClose(n1)) { out.push(pad() + t + n1); i += 1; continue; }
      out.push(pad() + t);
      depth++;
      continue;
    }
    out.push(pad() + t);
  }
  return out.join('\n');
}

export function formatPayload(kind: string, text: string): string {
  return kind === 'json' ? prettyJson(text) : prettyXml(text);
}
