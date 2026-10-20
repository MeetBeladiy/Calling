import type { DatabaseState } from "../models.js";

export interface Store {
  read(): Promise<DatabaseState>;
  mutate<T>(operation: (state: DatabaseState) => T | Promise<T>): Promise<T>;
}
