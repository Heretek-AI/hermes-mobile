// Test shims — the vendored renderer has vitest + @testing-library/react
// test files that we exclude from the mobile build. Type declarations
// for the test-only globals so the typechecker is happy even when those
// files are present on disk.

declare module "vitest" {
  export const describe: (name: string, fn: () => void) => void;
  export const it: (name: string, fn: () => void) => void;
  export const test: (name: string, fn: () => void) => void;
  export const expect: any;
  export const beforeEach: (fn: () => void) => void;
  export const afterEach: (fn: () => void) => void;
  export const beforeAll: (fn: () => void) => void;
  export const afterAll: (fn: () => void) => void;
  export const vi: any;
}

declare module "@testing-library/react" {
  export const render: any;
  export const screen: any;
  export const fireEvent: any;
  export const waitFor: any;
  export const cleanup: () => void;
}
