import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {installContestInputGuard} from '../../src/main/resources/static/contest-input-guard.mjs';
import {contestPayload, shouldRecordEvent, countdown, questionAccessKey, GROUP_PERMISSIONS} from '../src/lib/groupContracts.js';

test('all eleven group feature permissions are unique', () => {
  assert.equal(GROUP_PERMISSIONS.length, 11);
  assert.equal(new Set(GROUP_PERMISSIONS.map(([key]) => key)).size, 11);
});
test('started contest saves preserve original microseconds, title and ordered problem numbers', () => {
  for (const status of ['ACTIVE', 'ENDED']) {
    const current = {status, title: '원본 제목', startsAt: '2026-10-21T12:00:10.123456Z', endsAt: '2026-10-21T12:00:10.123457Z', problems: [{problemNumber: 1002}, {problemNumber: 1001}]};
    const body = contestPayload({title: '변경', startsAt: 'invalid', endsAt: 'invalid', problemNumbers: 'bad', replayAllowed: true, captureProtection: false, windowSwitchProtection: true}, current);
    assert.equal(body.title, current.title);
    assert.equal(body.startsAt, current.startsAt);
    assert.equal(body.endsAt, current.endsAt);
    assert.deepEqual(body.problemNumbers, [1002, 1001]);
    assert.equal(body.replayAllowed, true);
    assert.equal(body.captureProtection, false);
    assert.equal(body.windowSwitchProtection, true);
  }
});
test('new and upcoming contest validates duplicate, count, numbers and time boundaries', () => {
  const fields = {title: '시험', startsAt: '2030-10-21T12:00', endsAt: '2030-10-21T13:00', problemNumbers: '1002,1001'};
  assert.deepEqual(contestPayload(fields).problemNumbers, [1002, 1001]);
  for (const problemNumbers of ['', '1000,1000', '999', 'bad', Array.from({length: 51}, (_, i) => i + 1000).join(',')])
    assert.throws(() => contestPayload({...fields, problemNumbers}));
  assert.throws(() => contestPayload({...fields, endsAt: fields.startsAt}));
  assert.throws(() => contestPayload({...fields, startsAt: 'invalid'}));
});
test('monitoring signals are only recorded for enabled features during active interval', () => {
  const now = Date.now(), contest = {status: 'ACTIVE', endsAt: new Date(now + 1000).toISOString(), captureProtection: true, windowSwitchProtection: false};
  assert.equal(shouldRecordEvent(contest, 'COPY_BLOCKED', now), true);
  assert.equal(shouldRecordEvent(contest, 'WINDOW_BLUR', now), false);
  assert.equal(shouldRecordEvent({...contest, windowSwitchProtection: true}, 'WINDOW_BLUR', now), true);
  assert.equal(shouldRecordEvent(contest, 'COPY_BLOCKED', now + 1000), false);
  assert.equal(shouldRecordEvent({...contest, status: 'ENDED'}, 'COPY_BLOCKED', now), false);
  assert.equal(shouldRecordEvent(contest, 'UNKNOWN', now), false);
});
test('countdown never displays negative remaining time', () => {
  assert.equal(countdown({status: 'ACTIVE', endsAt: '2020-01-01T00:00:00Z'}, Date.now()), '0:00:00');
});
test('active protection option refresh preserves drafts but replay/time changes clear private question scope', () => {
  assert.equal(questionAccessKey({status:'ACTIVE', replayAllowed:false}), questionAccessKey({status:'ACTIVE', replayAllowed:true}));
  assert.notEqual(questionAccessKey({status:'ACTIVE'}), questionAccessKey({status:'ENDED', replayAllowed:false}));
  assert.notEqual(questionAccessKey({status:'ENDED', replayAllowed:false}), questionAccessKey({status:'ENDED', replayAllowed:true}));
});

test('exam input guard blocks clipboard before the editor and refreshes once per detected capture', async () => {
  const dom = new JSDOM('<main><textarea></textarea></main><input>', {url:'http://localhost/'});
  const {window} = dom, {document} = window, exam = document.querySelector('main'), area = exam.firstElementChild;
  const records = []; let active = true, reloads = 0, editorPastes = 0;
  area.addEventListener('paste', () => editorPastes++);
  const attach = captureProtection => installContestInputGuard({window, document, captureProtection,
    active: () => active, appliesTo: target => exam.contains(target),
    record: type => records.push(type), reload: () => reloads++});
  const clip = (target,type) => target.dispatchEvent(new window.Event(type,{bubbles:true,cancelable:true}));
  const key = (type,key,extra={}) => area.dispatchEvent(new window.KeyboardEvent(type,{key,bubbles:true,cancelable:true,...extra}));
  let detach = attach(false);
  for (const type of ['copy','cut','paste','drop']) assert.equal(clip(area,type),false,type);
  assert.equal(area.dispatchEvent(new window.InputEvent('beforeinput',{inputType:'insertFromPaste',bubbles:true,cancelable:true})),false);
  assert.equal(clip(document.querySelector('input'),'paste'),true);
  assert.equal(editorPastes,0);assert.deepEqual(records,[]);
  assert.equal(key('keyup','PrintScreen'),true);
  detach();detach=attach(true);
  assert.equal(key('keydown','PrintScreen'),false);
  await new Promise(resolve=>setImmediate(resolve));
  key('keyup','PrintScreen');await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(records,['CAPTURE_KEY']);assert.equal(reloads,1);
  key('keydown','s',{metaKey:true,shiftKey:true});assert.equal(reloads,1);
  active=false;assert.equal(clip(area,'paste'),true);assert.equal(editorPastes,1);
  detach();active=true;assert.equal(clip(area,'paste'),true);
  dom.window.close();
});
