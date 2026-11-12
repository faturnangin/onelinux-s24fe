const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const rootDir = __dirname;
const tmpDir = path.join(rootDir, 'temp_proot_dl');
const outDir = path.join(rootDir, 'app', 'src', 'main', 'jniLibs', 'arm64-v8a');
const ndkStrip = 'C:\\Users\\user\\AppData\\Local\\Android\\Sdk\\ndk\\30.0.16248370\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin\\llvm-strip.exe';

if (!fs.existsSync(outDir)) {
  fs.mkdirSync(outDir, { recursive: true });
}

const prootSrc = path.join(tmpDir, 'proot', 'data', 'data', 'com.termux', 'files', 'usr', 'bin', 'proot');
const loaderSrc = path.join(tmpDir, 'proot', 'data', 'data', 'com.termux', 'files', 'usr', 'libexec', 'proot', 'loader');
const tallocSrc = path.join(tmpDir, 'libtalloc', 'data', 'data', 'com.termux', 'files', 'usr', 'lib', 'libtalloc.so.2.5.0');
const shmemSrc = path.join(tmpDir, 'libandroid-shmem', 'data', 'data', 'com.termux', 'files', 'usr', 'lib', 'libandroid-shmem.so');

const prootDst = path.join(outDir, 'libproot.so');
const loaderDst = path.join(outDir, 'libproot-loader.so');
const tallocDst = path.join(outDir, 'libtalloc.so');
const shmemDst = path.join(outDir, 'libandroid-shmem.so');

fs.copyFileSync(prootSrc, prootDst);
fs.copyFileSync(loaderSrc, loaderDst);
fs.copyFileSync(tallocSrc, tallocDst);
fs.copyFileSync(shmemSrc, shmemDst);

function patchBinary(filePath, searchStr, replaceStr) {
  let buf = fs.readFileSync(filePath);
  const searchBuf = Buffer.from(searchStr, 'latin1');
  const replaceBuf = Buffer.from(replaceStr, 'latin1');
  
  if (searchBuf.length !== replaceBuf.length) {
    throw new Error('Replacement buffer length must equal search buffer length');
  }

  let count = 0;
  let idx = 0;
  while ((idx = buf.indexOf(searchBuf, idx)) !== -1) {
    replaceBuf.copy(buf, idx);
    count++;
    idx += searchBuf.length;
  }
  fs.writeFileSync(filePath, buf);
  console.log(`Patched ${count} occurrence(s) in ${path.basename(filePath)}`);
}

patchBinary(prootDst, 'libtalloc.so.2\0', 'libtalloc.so\0\0\0');
patchBinary(tallocDst, 'libtalloc.so.2\0', 'libtalloc.so\0\0\0');

if (fs.existsSync(ndkStrip)) {
  console.log('Stripping binaries with llvm-strip...');
  execSync(`"${ndkStrip}" "${prootDst}"`);
  execSync(`"${ndkStrip}" "${loaderDst}"`);
}

console.log('Successfully prepared jniLibs/arm64-v8a:');
for (const f of fs.readdirSync(outDir)) {
  const stats = fs.statSync(path.join(outDir, f));
  console.log(`- ${f} (${stats.size} bytes)`);
}
