// Test-only stdio server: `node serve-with-tools.ts <bridge port> <tool name>...`.
// The production entry point takes no tool list, so this file is the only way to run src/server.ts with tools today.
import { serve } from '../src/server.ts';

const [port, ...names] = process.argv.slice(2);
serve({
  port: Number(port),
  tools: names.map((name) => ({ name, title: `Test ${name}`, description: `Test tool ${name}` })),
});
