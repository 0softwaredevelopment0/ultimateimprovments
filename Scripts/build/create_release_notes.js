#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const buildFile = fs.readFileSync(path.join(root, 'build.gradle'), 'utf8');
const changelog = fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8');

const version = process.argv[2] || (buildFile.match(/version\\s*=\\s*'([^']+)'/) || [])[1];
if (!version) {
  throw new Error('Could not determine project version from build.gradle.');
}

const match = version.match(/^(\\d+\\.\\d+\\.\\d+-beta\\.)(\\d+)$/);
if (!match) {
  throw new Error(`Expected a beta version such as 1.8.5-beta.21, received: ${version}`);
}

const prefix = match[1];
const headings = [...changelog.matchAll(/^## \\[([^\\]]+)\\].*$/gm)];
const startHeading = headings.find(entry => entry[1] === version);
if (!startHeading || startHeading.index == null) {
  throw new Error(`No CHANGELOG.md section found for ${version}.`);
}

let end = changelog.length;
for (const heading of headings) {
  if (heading.index <= startHeading.index) continue;
  if (!heading[1].startsWith(prefix)) {
    end = heading.index;
    break;
  }
}

const notes = changelog.slice(startHeading.index, end).trim();
if (!new RegExp(`^## \\\\[\\s*${prefix.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&')}1\\s*\\]`, 'm').test(notes)) {
  throw new Error('Release notes do not include the 1.8.5-beta.1 baseline.');
}

process.stdout.write(`# UltimateImprovments ${version}

Changes from 1.8.5-beta.1 through ${version}.

${notes}
\\n`);
