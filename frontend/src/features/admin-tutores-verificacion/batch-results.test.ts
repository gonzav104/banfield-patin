import { describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api-error';
import { classify, failureText } from '@/features/adultos-familia/messages';
import { batchResultOk, batchResultText } from './messages';

describe('batch and own-profile outcomes in plain words', () => {
  it('an unexpected failure and a batch that ran out of time are not successes and say what to do', () => {
    expect(batchResultText('ERROR')).toMatch(/intentá de nuevo/);
    expect(batchResultText('NO_PROCESADO')).toMatch(/volvé a verificarla/);
    expect(batchResultOk('ERROR')).toBe(false);
    expect(batchResultOk('NO_PROCESADO')).toBe(false);
    // Previous behaviour.
    expect(batchResultOk('VERIFICADO')).toBe(true);
    expect(batchResultText('OCUPADO')).toMatch(/ocupado/);
  });

  it('PERFIL_PROPIO points the person to «Mis datos» without echoing anything', () => {
    const error = new ApiError('conflict', 'texto del servidor', { status: 409, code: 'PERFIL_PROPIO' });
    expect(classify(error)).toBe('ownProfile');
    expect(failureText(classify(error), error)).toBe('Tus propios datos se cambian desde «Mis datos».');
  });
});
