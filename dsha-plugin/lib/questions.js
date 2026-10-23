import { setTimeout as delay } from 'node:timers/promises';

export function validateHostAnswer(questions, value) {
  if (!Array.isArray(value?.answers) || value.answers.length !== questions.length) throw new Error('Incomplete question answer');
  const ids = new Set();
  for (const answer of value.answers) {
    const q = questions.find(item => item.id === answer.id);
    if (!q || ids.has(answer.id) || !Array.isArray(answer.selected)) throw new Error('Invalid question identity');
    ids.add(answer.id);
    const custom = typeof answer.custom === 'string' ? answer.custom.trim() : '';
    if (!answer.selected.length && !custom) throw new Error('Empty answer');
    if (!q.multiSelect && (answer.selected.length > 1 || (answer.selected.length && custom))) throw new Error('Invalid single choice');
    if (new Set(answer.selected).size !== answer.selected.length || answer.selected.some(label => !q.options?.some(o => o.label === label))) throw new Error('Invalid choice');
  }
  return { answers: value.answers.map(a => ({ id: a.id, selected: a.selected, ...(a.custom ? { custom: a.custom } : {}) })) };
}

// Both answerers compete for one waterfall result. The losing Web request is
// aborted through its child signal, never through the Agent's turn signal.
export async function answerFromIsland({ request, next, post, key, signal, pollMs = 500 }) {
  const local = new AbortController();
  const original = request.signal;
  const combined = AbortSignal.any([local.signal, ...(original ? [original] : []), ...(signal ? [signal] : [])]);
  const query = new URLSearchParams(key).toString();
  let accepted = false;
  request.signal = combined;
  const host = Promise.resolve().then(() => next()).then(answer => ({ answer, source: 'host' })).catch(async error => {
    if (error?.code !== 'NO_PROVIDER' || combined.aborted) throw error;
    await delay(2147483647, undefined, { signal: combined });
  });
  const island = (async () => {
    while (!combined.aborted) {
      let value;
      try { value = await post('/api/question?' + query, undefined, { signal: combined }); }
      catch (error) { if (combined.aborted) throw error; }
      if (value?.state === 'submitted') return { answer: validateHostAnswer(request.questions, value), source: 'island' };
      if (value?.state === 'cancelled' || value?.state === 'answered') throw new Error('Question is no longer answerable');
      await delay(pollMs, undefined, { signal: combined });
    }
    throw combined.reason;
  })();
  try {
    const winner = await Promise.race([host, island]);
    if (combined.aborted) throw combined.reason;
    const answer = validateHostAnswer(request.questions, winner.answer);
    accepted = true;
    // Queue acceptance before restoring the task phase. A gateway failure cannot
    // invalidate an answer that has already won the host waterfall.
    await post('/api/question/settle', { ...key, state: 'answered', ...answer, answer_source: winner.source }, { timeoutMs: 2000, signal: combined }).catch(() => {});
    if (combined.aborted) throw combined.reason;
    return answer;
  } finally {
    local.abort(new Error('Question answered or closed'));
    request.signal = original;
    if (!accepted) await post('/api/question/settle', { ...key, state: 'cancelled' }, { timeoutMs: 2000 }).catch(() => {});
  }
}
