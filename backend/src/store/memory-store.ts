import { emptyDatabase, type DatabaseState } from "../models.js";
import type { Store } from "./store.js";

export class MemoryStore implements Store {
  protected state: DatabaseState;
  private queue: Promise<void> = Promise.resolve();

  constructor(initial: DatabaseState = emptyDatabase()) {
    this.state = structuredClone(initial);
  }

  async read(): Promise<DatabaseState> {
    await this.queue;
    return structuredClone(this.state);
  }

  async mutate<T>(operation: (state: DatabaseState) => T | Promise<T>): Promise<T> {
    let result!: T;
    let failure: unknown;
    this.queue = this.queue.then(async () => {
      try {
        result = await operation(this.state);
        await this.afterMutation();
      } catch (error) {
        failure = error;
      }
    });
    await this.queue;
    if (failure) throw failure;
    return result;
  }

  protected async afterMutation(): Promise<void> {}
}
