import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

// Asynchronous queries (findBy*, waitFor) wait up to 3 s instead of the 1 s default. Under CPU contention (several suites
// on one machine, a busy CI runner) 1 s was not enough and different tests failed at random; 3 s stays below the 5 s limit
// of a whole test, so a real failure is still reported as the missing element and not as a test timeout.
configure({ asyncUtilTimeout: 3000 });
