#!/usr/bin/env node
// Packages the BitBoard Electron app into a distributable build.
// Usage: npm run package            (current platform/arch)
//        npm run package:win        (win32 x64)
//        node scripts/package.js --linux --x64
'use strict';

const path = require('path');

async function main() {
    const { packager } = await import('@electron/packager');

    const args = process.argv.slice(2);
    const getFlag = (name, fallback) => {
        const i = args.indexOf(`--${name}`);
        return i !== -1 ? args[i + 1] : fallback;
    };

    const opts = {
        dir: path.join(__dirname, '..'),
        name: 'BitBoard',
        platform: getFlag('platform', process.platform),
        arch: getFlag('arch', process.arch),
        out: 'dist',
        overwrite: true,
        // Never bundle build output, tests, or scratch files.
        ignore: [/^\/dist$/, /^\/test$/, /^\/_pkg/, /^\/scripts$/],
    };

    console.log(`Packaging BitBoard for ${opts.platform} ${opts.arch}...`);
    const outPaths = await packager(opts);
    console.log('Packaged to:', outPaths.join(', '));
}

main().catch((err) => {
    console.error('Packaging failed:', err && (err.stack || err.message || err));
    process.exit(1);
});