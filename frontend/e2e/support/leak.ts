/**
 * What identifies ONE athlete's data on a page: a pattern that matches when it is there and nothing else. It is built from
 * specific, labelled values, never from a bare short string: the family names carry a random hex suffix, so a bare `007` also
 * matched «E2E Ficha Familia B 000728» and failed a test that had leaked nothing.
 */
export function leakPattern(data: { athleteName: string; runnerNumber: string; category: string; division: string; familyName: string }): RegExp {
  const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(
    [
      escape(data.athleteName),
      `Número de corredor\\s+${escape(data.runnerNumber)}(?!\\w)`, // the number only as the VALUE of its labelled row, not as a substring of anything
      escape(data.category),
      escape(data.division),
      escape(data.familyName),
    ].join('|'),
  );
}
