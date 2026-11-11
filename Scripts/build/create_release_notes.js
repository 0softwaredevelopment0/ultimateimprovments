#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const buildFile = fs.readFileSync(path.join(root, 'build.gradle'), 'utf8');
const changelog = fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8');

const versionLine = buildFile.split('\n').find(line => line.trim().startsWith('version = '));
const version = process.argv[2] || (versionLine ? versionLine.split("'")[1] : null);
if (!version) {
  throw new Error('Could not determine project version from build.gradle.');
}

const lastDot = version.lastIndexOf('.');
const prefix = version.slice(0, lastDot + 1);
const betaNumber = Number(version.slice(lastDot + 1));
if (!version.includes('-beta.') || !Number.isInteger(betaNumber) || betaNumber < 1) {
  throw new Error('Expected a beta version such as 1.8.5-beta.21; received: ' + version);
}

const selected = [];
let started = false;

for (const rawLine of changelog.split('\n')) {
  const line = rawLine.replace(/\r$/, '');

  if (line.startsWith('## [')) {
    const closingBracket = line.indexOf(']', 4);
    if (closingBracket < 0) {
      throw new Error('Malformed changelog heading: ' + line);
    }

    const sectionVersion = line.slice(4, closingBracket);
    if (!started && sectionVersion === version) {
      started = true;
    } else if (started && !sectionVersion.startsWith(prefix)) {
      break;
    }
  }

  if (started) selected.push(line);
}

if (!started) {
  throw new Error('No CHANGELOG.md section found for ' + version + '.');
}

const betaOneHeading = '## [' + prefix + '1]';
if (!selected.some(line => line.startsWith(betaOneHeading))) {
  throw new Error('Release notes do not include the 1.8.5-beta.1 baseline.');
}

const notes = selected.join('\n').trim();
process.stdout.write(
  '# UltimateImprovments ' + version +
  '\n\nChanges from 1.8.5-beta.1 through ' + version + '.\n\n' +
  notes + '\n'
);
