// Vendored from hermes-desktop/src/shared/attachments.ts (162 LOC).
// Mobile keeps a minimal subset — the renderer's Attachment type plus a
// few constants the renderer imports at module scope.

export type AttachmentKind = "image" | "text-file" | "path-ref";

export interface Attachment {
  id: string;
  kind: AttachmentKind;
  name: string;
  mime: string;
  size: number;
  dataUrl?: string;
  originalSize?: number;
  text?: string;
  path?: string;
}

export const MAX_IMAGE_INPUT_BYTES = 50 * 1024 * 1024;
export const MAX_IMAGE_TARGET_BYTES = 5 * 1024 * 1024;
export const MAX_TEXT_BYTES = 256 * 1024;
export const MAX_ATTACHMENTS_PER_MESSAGE = 10;
