import { mkdir, readFile, rm, writeFile, cp } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = dirname(scriptDirectory);
const outputDirectory = join(projectRoot, "www");

const entriesToCopy = [
  "index.html",
  "styles.css",
  "src",
];

async function copyProjectEntry(relativePath) {
  const sourcePath = join(projectRoot, relativePath);
  const destinationPath = join(outputDirectory, relativePath);

  await cp(sourcePath, destinationPath, { recursive: true });
}

async function writeBuildMetadata() {
  const packageJsonPath = join(projectRoot, "package.json");
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8"));
  const metadata = {
    name: packageJson.name,
    version: packageJson.version,
    builtAt: new Date().toISOString(),
  };

  await writeFile(join(outputDirectory, "build-meta.json"), `${JSON.stringify(metadata, null, 2)}\n`, "utf8");
}

async function buildWebBundle() {
  await rm(outputDirectory, { recursive: true, force: true });
  await mkdir(outputDirectory, { recursive: true });

  for (const entry of entriesToCopy) {
    await copyProjectEntry(entry);
  }

  await writeBuildMetadata();
}

await buildWebBundle();
