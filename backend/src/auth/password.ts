import { randomBytes, scrypt as scryptCallback, timingSafeEqual } from "node:crypto";

const scrypt = (password: string, salt: Buffer, length: number, options: { N: number; r: number; p: number }) =>
  new Promise<Buffer>((resolve, reject) => scryptCallback(password, salt, length, options, (error, key) => error ? reject(error) : resolve(key)));
const KEY_LENGTH = 64;
const COST = 16_384;

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const derived = await scrypt(password, salt, KEY_LENGTH, { N: COST, r: 8, p: 1 });
  return `scrypt$${COST}$${salt.toString("base64url")}$${derived.toString("base64url")}`;
}

export async function verifyPassword(password: string, encoded: string): Promise<boolean> {
  const [algorithm, costText, saltText, hashText] = encoded.split("$");
  if (algorithm !== "scrypt" || !costText || !saltText || !hashText) return false;
  const cost = Number(costText);
  if (!Number.isInteger(cost) || cost < COST) return false;
  const expected = Buffer.from(hashText, "base64url");
  const actual = await scrypt(password, Buffer.from(saltText, "base64url"), expected.length, {
    N: cost,
    r: 8,
    p: 1
  });
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
