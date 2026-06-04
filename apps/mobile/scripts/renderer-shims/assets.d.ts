// Asset stubs for the vendored renderer. The desktop ships
// `hermesbg.webp`, `splashtext-w.webp`, and various PNG/SVG icons in
// `src/assets/`. The mobile shell includes only the assets it
// actually needs; for the rest, we declare ambient module types so
// `import x from "../../assets/foo.png"` typechecks. Vite's default
// asset handler resolves the path at bundle time; if the file is
// missing the bundle fails, so for the mobile build we just need
// type-level compatibility — the renderer only renders these in
// splash/welcome screens we'll redesign in Phase 5.

declare module "*.png" {
  const url: string;
  export default url;
}

declare module "*.svg" {
  const url: string;
  export default url;
}

declare module "*.jpg" {
  const url: string;
  export default url;
}

declare module "*.jpeg" {
  const url: string;
  export default url;
}

declare module "*.webp" {
  const url: string;
  export default url;
}

declare module "*.gif" {
  const url: string;
  export default url;
}

declare module "*.ico" {
  const url: string;
  export default url;
}

declare module "*.css" {
  const content: string;
  export default content;
}
