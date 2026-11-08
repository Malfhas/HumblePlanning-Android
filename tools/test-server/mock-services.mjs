// Stand-ins for the external services of the test server, so that tests need no
// account and spend nothing: the push relay and Firebase (they record what they
// receive), and an OpenRouter-compatible model that answers from a script.
import { createServer } from "node:http";

const pushes = [];
const deadTokens = new Set();

const MODEL = "test/model";

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function chatChunk(delta, finishReason = null) {
  return `data: ${JSON.stringify({
    id: "chatcmpl-test",
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model: MODEL,
    choices: [{ index: 0, delta, finish_reason: finishReason }],
  })}\n\n`;
}

/**
 * Scripted answer for the README screenshots, which use a fictitious data set
 * where Liam Turner is busy until Wednesday.
 */
const SCREENSHOT_ANSWER = [
  "**Liam Turner** is free on **Thursday 12** and **Friday 13 November**.\n\n",
  "- **Monday 9 and Tuesday 10:** switchboard replacement at Harbour View Offices, with Emma Collins\n",
  "- **Wednesday 11:** Armistice Day, public holiday\n\n",
  "Nothing is planned for him on Thursday and Friday: he could take the EV charger installation at Greenfield Warehouse. ",
  "Shall I prepare that task?",
];

/**
 * Last user message decides the answer: "crée une tâche" asks for a write tool,
 * so that tests go through the approval; a question about who is available gets
 * the scripted answer above; anything else is echoed as text.
 */
function decodeArguments(text) {
  // The message reaches the model as JSON text inside a content array
  try { return JSON.stringify(JSON.parse(text)); } catch { /* escaped */ }
  try { return JSON.stringify(JSON.parse(JSON.parse(`"${text.replace(/"}]$/, "")}"`))); } catch { return "{}"; }
}

function chatCompletion(body, res) {
  const messages = body.messages ?? [];
  const last = messages[messages.length - 1];
  const lastText = typeof last?.content === "string" ? last.content : JSON.stringify(last?.content ?? "");
  const writeTool = (body.tools ?? []).find((tool) => tool.function?.name === "create-task");
  const wantsWrite = last?.role === "user" && /cr[ée]e une t[âa]che/i.test(lastText) && writeTool;

  if (!body.stream) {
    send(res, 200, {
      id: "chatcmpl-test", object: "chat.completion", created: Math.floor(Date.now() / 1000), model: MODEL,
      choices: [{ index: 0, message: { role: "assistant", content: "Titre de test" }, finish_reason: "stop" }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    });
    return;
  }

  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
  if (wantsWrite) {
    res.write(chatChunk({ role: "assistant", tool_calls: [{ index: 0, id: "call_test", type: "function", function: { name: "create-task", arguments: "" } }] }));
    // The test gives the arguments after "::", valid for the instance it runs on
    const toolArguments = lastText.split("::")[1]?.trim().replace(/"}?]?}?$/, (end) => end) ?? "{}";
    res.write(chatChunk({ tool_calls: [{ index: 0, function: { arguments: decodeArguments(toolArguments) } }] }));
    res.write(chatChunk({}, "tool_calls"));
  } else {
    res.write(chatChunk({ role: "assistant", content: "" }));
    const scripted = last?.role === "user" && /\bavailable\b/i.test(lastText);
    for (const word of scripted ? SCREENSHOT_ANSWER : ["Réponse ", "de ", "test : ", lastText.slice(0, 80)]) res.write(chatChunk({ content: word }));
    res.write(chatChunk({}, "stop"));
  }
  // A question that says "costly" costs 1 $, enough to use up a quota of a few cents (quota tests)
  const cost = last?.role === "user" && /\bcostly\b/i.test(lastText) ? 1 : 0.000001;
  res.write(`data: ${JSON.stringify({
    id: "chatcmpl-test", object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: MODEL,
    choices: [], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2, cost },
  })}\n\n`);
  res.end("data: [DONE]\n\n");
}

const server = createServer((req, res) => {
  const chunks = [];
  req.on("data", (chunk) => chunks.push(chunk));
  req.on("end", () => {
    const raw = Buffer.concat(chunks).toString();
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { /* form-encoded */ }
    const path = req.url.split("?")[0];

    if (req.method === "GET" && path === "/__pushes") return send(res, 200, pushes);
    if (req.method === "POST" && path === "/__reset") {
      pushes.length = 0;
      deadTokens.clear();
      return send(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/__dead") {
      deadTokens.add(body.fid);
      return send(res, 200, { ok: true });
    }

    // Push relay: `{fid, alertId}`, the Firebase Installation ID of the device
    if (req.method === "POST" && path === "/v1/push") {
      if (typeof body.fid !== "string" || !body.fid) return send(res, 400, { error: "invalid_body" });
      if (deadTokens.has(body.fid)) return send(res, 404, { error: "firebase_refused", status: 404 });
      pushes.push({ via: "relay", fid: body.fid, alertId: body.alertId, authorization: req.headers.authorization ?? null });
      return send(res, 200, { status: "sent" });
    }

    // Firebase
    if (req.method === "POST" && path === "/oauth/token") {
      return send(res, 200, { access_token: "test-access-token", expires_in: 3600, token_type: "Bearer" });
    }
    if (req.method === "POST" && /^\/v1\/projects\/[^/]+\/messages:send$/.test(path)) {
      // HTTP v1 targets the Firebase Installation ID with `message.fid`; `token` is the deprecated registration token
      const message = body.message ?? {};
      if (typeof message.fid !== "string" || message.token !== undefined) return send(res, 400, { error: { status: "INVALID_ARGUMENT" } });
      if (deadTokens.has(message.fid)) return send(res, 404, { error: { status: "UNREGISTERED" } });
      pushes.push({ via: "firebase", fid: message.fid, data: message.data, priority: message.android?.priority });
      return send(res, 200, { name: "projects/test/messages/1" });
    }

    // OpenRouter
    if (req.method === "GET" && path === "/api/v1/models") {
      return send(res, 200, { data: [{
        id: MODEL, name: "Test Model",
        pricing: { prompt: "0.000001", completion: "0.000001" },
        architecture: { input_modalities: ["text", "file", "image"], output_modalities: ["text"] },
        supported_parameters: ["tools", "reasoning"],
      }] });
    }
    if (req.method === "POST" && path === "/api/v1/chat/completions") return chatCompletion(body, res);

    send(res, 404, { error: `no mock for ${req.method} ${path}` });
  });
});

server.listen(4000, () => console.log("Mock services on port 4000"));
