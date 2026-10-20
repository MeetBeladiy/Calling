import "dotenv/config";
import { z } from "zod";

const booleanValue = z.enum(["true", "false"]).transform((value) => value === "true");

const schema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  HOST: z.string().default("0.0.0.0"),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  JWT_SECRET: z.string().min(32).default("development-only-secret-change-me-123456"),
  JWT_ISSUER: z.string().default("calling-app"),
  ACCESS_TOKEN_TTL: z.string().default("15m"),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().min(1).max(365).default(30),
  DATA_FILE: z.string().default("./data/calling-app.json"),
  CORS_ORIGINS: z.string().default("http://localhost:3000"),
  TRUST_PROXY: booleanValue.default("false"),
  STUN_URLS: z.string().default("stun:stun.l.google.com:19302"),
  TURN_URLS: z.string().default(""),
  TURN_USERNAME: z.string().default(""),
  TURN_CREDENTIAL: z.string().default("")
});

export type AppConfig = {
  env: "development" | "test" | "production";
  host: string;
  port: number;
  jwtSecret: string;
  jwtIssuer: string;
  accessTokenTtl: string;
  refreshTokenTtlDays: number;
  dataFile: string;
  corsOrigins: string[];
  trustProxy: boolean;
  stunUrls: string[];
  turnUrls: string[];
  turnUsername: string;
  turnCredential: string;
};

export function loadConfig(source: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = schema.parse(source);
  if (parsed.NODE_ENV === "production" && parsed.JWT_SECRET.startsWith("development-only")) {
    throw new Error("JWT_SECRET must be set to a strong random value in production");
  }
  const split = (value: string) => value.split(",").map((item) => item.trim()).filter(Boolean);
  return {
    env: parsed.NODE_ENV,
    host: parsed.HOST,
    port: parsed.PORT,
    jwtSecret: parsed.JWT_SECRET,
    jwtIssuer: parsed.JWT_ISSUER,
    accessTokenTtl: parsed.ACCESS_TOKEN_TTL,
    refreshTokenTtlDays: parsed.REFRESH_TOKEN_TTL_DAYS,
    dataFile: parsed.DATA_FILE,
    corsOrigins: split(parsed.CORS_ORIGINS),
    trustProxy: parsed.TRUST_PROXY,
    stunUrls: split(parsed.STUN_URLS),
    turnUrls: split(parsed.TURN_URLS),
    turnUsername: parsed.TURN_USERNAME,
    turnCredential: parsed.TURN_CREDENTIAL
  };
}
