// Vendored from hermes-desktop/src/shared/registry.ts (58 LOC).
// Community-registry types referenced by the IPC contract.

export type RegistryKind = "skills" | "mcps" | "agents" | "workflows";

export interface RegistryItem {
  id: string;
  name: string;
  description: string;
  author?: string;
  category?: string;
  tags?: string[];
  homepage?: string;
  version?: string;
  license?: string;
  platforms?: string[];
  path?: string;
  source?: string;
}

export interface RegistryCatalog {
  skills: RegistryItem[];
  mcps: RegistryItem[];
  agents: RegistryItem[];
  workflows: RegistryItem[];
}

export interface RegistryDetailRow {
  label: string;
  value?: string;
  mono?: boolean;
  chips?: string[];
}

export interface RegistryDetail {
  markdown?: string;
  description?: string;
  rows?: RegistryDetailRow[];
}
