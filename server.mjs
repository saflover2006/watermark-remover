import { createReadStream, promises as fs } from "node:fs";
import { createServer } from "node:http";
import { extname } from "node:path";
import { fileURLToPath } from "node:url";

const port = 4173;
const rootPath = fileURLToPath(new URL("./", import.meta.url));
const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
};

function sendText(response, statusCode, body) {
  response.writeHead(statusCode, {
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "no-store",
  });
  response.end(body);
}

const server = createServer(async (request, response) => {
  try {
    const requestUrl = new URL(request.url ?? "/", "http://localhost");
    const requestedPath = requestUrl.pathname === "/" ? "./index.html" : `.${requestUrl.pathname}`;
    const fileUrl = new URL(requestedPath, import.meta.url);
    const filePath = fileURLToPath(fileUrl);

    if (!filePath.startsWith(rootPath)) {
      sendText(response, 403, "Forbidden");
      return;
    }

    const stats = await fs.stat(filePath);

    if (stats.isDirectory()) {
      sendText(response, 403, "Directory listing is disabled");
      return;
    }

    response.writeHead(200, {
      "Content-Type": mimeTypes[extname(filePath)] ?? "application/octet-stream",
      "Cache-Control": "no-store",
    });

    createReadStream(filePath).pipe(response);
  } catch (error) {
    if (error && error.code === "ENOENT") {
      sendText(response, 404, "Not found");
      return;
    }

    console.error(error);
    sendText(response, 500, "Unexpected server error");
  }
});

server.listen(port, () => {
  console.log(`Watermark Remover Studio is running at http://localhost:${port}`);
});
