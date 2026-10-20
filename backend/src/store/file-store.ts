import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { emptyDatabase, type DatabaseState } from "../models.js";
import { MemoryStore } from "./memory-store.js";

export class FileStore extends MemoryStore {
  private constructor(private readonly filePath: string, initial: DatabaseState) {
    super(initial);
  }

  static async open(filePath: string): Promise<FileStore> {
    const absolute = resolve(filePath);
    await mkdir(dirname(absolute), { recursive: true });
    try {
      const parsed = JSON.parse(await readFile(absolute, "utf8")) as DatabaseState;
      return new FileStore(absolute, { ...emptyDatabase(), ...parsed });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      return new FileStore(absolute, emptyDatabase());
    }
  }

  protected override async afterMutation(): Promise<void> {
    const temporary = `${this.filePath}.tmp`;
    await writeFile(temporary, JSON.stringify(this.state, null, 2), { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.filePath);
  }
}
