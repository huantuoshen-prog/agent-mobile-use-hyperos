import test from 'node:test';
import assert from 'node:assert/strict';
import { answerFromIsland, validateHostAnswer } from '../lib/questions.js';
const questions = [{ id: 'q', question: '继续？', options: [{ label: '继续' }, { label: '停止' }] }];
const answer = { answers: [{ id: 'q', selected: ['继续'] }] };
const key = { request_id: 'r', session_id: 's', turn_id: 't' };
test('岛内回答返回原始问题ID和选项，取消Web等待但不取消Agent', async () => {
  const parent = new AbortController(); const request = { questions, signal: parent.signal }; const sent=[];
  let webSignal;
  const result=await answerFromIsland({ request, key, pollMs:1,
    next:()=>new Promise((_,reject)=>{webSignal=request.signal;webSignal.addEventListener('abort',()=>reject(webSignal.reason),{once:true});}),
    post:async (route,body)=>{sent.push({route,body});return {state:'submitted',...answer};} });
  assert.deepEqual(result,answer);assert.equal(webSignal.aborted,true);assert.equal(parent.signal.aborted,false);
  assert.equal(request.signal,parent.signal);assert.equal(sent.at(-1).body.state,'answered');
  assert.equal(sent.at(-1).body.answer_source,'island');assert.deepEqual(sent.at(-1).body.answers,answer.answers);
});
test('Web先回答时回收岛请求，旧按钮不覆盖结果',async()=>{
  const sent=[];const result=await answerFromIsland({request:{questions},key,next:async()=>answer,post:async(route,body)=>{sent.push({route,body});return {state:'open'};},pollMs:1});
  assert.deepEqual(result,answer);assert.equal(sent.at(-1).body.state,'answered');
  assert.equal(sent.at(-1).body.answer_source,'host');assert.deepEqual(sent.at(-1).body.answers,answer.answers);
});
test('父任务取消时关闭问题且不返回答案',async()=>{
  const parent=new AbortController();const sent=[];
  const pending=answerFromIsland({request:{questions,signal:parent.signal},key,next:()=>new Promise(()=>{}),post:async(route,body)=>{sent.push({route,body});return {state:'open'};},pollMs:1});
  parent.abort(new Error('cancelled'));await assert.rejects(pending);assert.equal(sent.at(-1).body.state,'cancelled');
});
test('答案不允许其他问题、伪造选项、重复选项或空答案',()=>{
  for(const answers of [[{id:'other',selected:['继续']}],[{id:'q',selected:['伪造']}],[{id:'q',selected:['继续','继续']}],[{id:'q',selected:[]}]])assert.throws(()=>validateHostAnswer(questions,{answers}));
  assert.deepEqual(validateHostAnswer(questions,{answers:[{id:'q',selected:[],custom:'稍后'}]}).answers[0].custom,'稍后');
});
