const assert = require('node:assert/strict');
const fs = require('node:fs');
const readline = require('node:readline');
const A = require('../target/interop/node_modules/@automerge/automerge');

if (process.argv[2] === 'document') {
  let doc = A.load(fs.readFileSync('target/interop/clojure.automerge'));
  assert.equal(doc.body, '😀Hello');
  assert.equal(doc.title.toString(), 'literal');
  assert.ok(doc.title instanceof A.ImmutableString);
  assert.equal(doc.count.value, 1);
  assert.deepEqual(doc.items, [1, 2]);
  doc = A.change(doc, d => {
    A.splice(d, ['body'], 2, 0, ' from JS ');
    d.count.increment(2);
    d.items.push(3);
    d.fromJS = 'collaborative';
  });
  fs.writeFileSync('target/interop/javascript.automerge', A.save(doc));
  console.log('Automerge 3.5.0 read and edited the Clojure document.');
} else if (process.argv[2] === 'sync') {
  let doc = A.change(A.init(), d => { d.fromJS = 'over sync'; });
  let state = A.initSyncState();
  const input = readline.createInterface({ input: process.stdin });
  input.on('line', line => {
    if (line === 'save') {
      console.log(Buffer.from(A.save(doc)).toString('base64'));
      input.close();
      return;
    }
    if (line !== '-') [doc, state] = A.receiveSyncMessage(doc, state, Buffer.from(line, 'base64'));
    let message;
    [state, message] = A.generateSyncMessage(doc, state);
    console.log(message ? Buffer.from(message).toString('base64') : '-');
  });
} else if (process.argv[2] === 'rich-text') {
  let doc = A.load(fs.readFileSync('target/interop/rich-clojure.automerge'));
  const spans = A.spans(doc, ['body']);
  assert.equal(spans[0].type, 'block');
  assert.equal(spans[0].value.type.toString(), 'paragraph');
  assert.equal(spans[1].value, 'Hello');
  assert.equal(spans[1].marks.strong, true);
  const end = doc.endCursor.toString();
  assert.equal(A.getCursorPosition(doc, ['body'], end), doc.body.length);
  doc = A.change(doc, d => {
    A.updateBlock(d, ['body'], 0, {type: 'heading', parents: [], attrs: {level: 2}});
    A.splitBlock(d, ['body'], d.body.length, {type: 'paragraph', parents: []});
    A.splice(d, ['body'], d.body.length, 0, 'From JS');
    d.jsEndCursor = new A.ImmutableString(A.getCursor(d, ['body'], 'end'));
  });
  fs.writeFileSync('target/interop/rich-javascript.automerge', A.save(doc));
  console.log('Automerge 3.5.0 round-tripped blocks, marks and endpoint cursors.');
} else if (process.argv[2] === 'rich-check') {
  const doc = A.load(fs.readFileSync('target/interop/rich-final.automerge'));
  assert.equal(A.spans(doc, ['body'])[0].value.attrs.level, 3);
  assert.equal(A.spans(doc, ['body']).filter(s => s.type === 'block').length, 1);
  assert.equal(A.getCursorPosition(doc, ['body'], doc.jsEndCursor.toString()), doc.body.length);
} else throw new Error('Expected document, sync, rich-text or rich-check mode');
