import {readFileSync} from 'node:fs';
import {RANKS} from '../src/lib/problemModel.js';

// This module is a Vite development server plugin, never a browser dependency.
// Use an ID outside PostgreSQL bigint so preview drafts cannot belong to a real user.
const PROFILE = {
  id: '9999999999999999999', username: '분석용 계정',
  bio: '로컬 화면 분석용 계정입니다.', rank_type: 'INITIATE', rank_int: 5,
  rank_points: 0, next_rank_points: 100, solved_count: 0, submit_count: 0, solvedProblems: [], topLanguages: [],
};
const TOKEN = `${Buffer.from('{"alg":"ANALYSIS"}').toString('base64url')}.${Buffer.from(JSON.stringify({sub: PROFILE.id})).toString('base64url')}.local-preview-only`;
const LANGUAGES = [
  ['PYTHON','Python'], ['C','C'], ['CPP','C++'], ['JAVA','Java'],
  ['JAVASCRIPT','JavaScript'], ['TYPESCRIPT','TypeScript'], ['CSHARP','C#'],
  ['GO','Go'], ['RUST','Rust'], ['KOTLIN','Kotlin'], ['RUBY','Ruby'],
  ['LLVM_IR','LLVM IR'], ['PASCAL','Pascal'], ['PHP','PHP'], ['DART','Dart'],
].map(([id,label]) => ({id,label,modes:['FUNCTION']}));
const SLOWER_LANGUAGE_FACTORS = {
  PYTHON: 2, RUBY: 2, PHP: 2, JAVA: 1.5, KOTLIN: 1.5, CSHARP: 1.5,
  JAVASCRIPT: 1.5, TYPESCRIPT: 1.5, DART: 1.5,
};
const STATIC_FILES = new Set(['/markdown.js','/code-editor.js','/react-bridge.js','/vendor/marked.umd.js']);

// Explicit public fields only: catalogs also contain private tests and solutions.
export function publicProblem(problem) {
  return {
    problem_number: problem.number, problem_title: problem.title,
    description: problem.description, input_description: problem.inputDescription,
    output_description: problem.outputDescription, constraints_text: problem.constraints,
    rank_type: problem.rankType, rank_int: problem.rankInt,
    reward_points: 10*(1+5*RANKS.indexOf(problem.rankType)+5-problem.rankInt),
    ...(problem.number>=1346 && {languageTimeLimits:Object.fromEntries(LANGUAGES.map(({id})=>[id,
      Math.min(10000,Math.ceil(problem.timeLimitMs*(SLOWER_LANGUAGE_FACTORS[id]??1)))]))}),
    time_limit_ms: problem.timeLimitMs, memory_limit_mb: problem.memoryLimitMb,
    execution_mode: 'FUNCTION', functionAbi: 'GENERIC_V1',
    functionSignature: {
      name: problem.functionSignature.name, returnType: problem.functionSignature.returnType,
      parameters: problem.functionSignature.parameters.map(({name,type}) => ({name,type})),
    },
    examples: problem.examples.map(({input,output,explanation}) => ({input,output,explanation})),
    tags: [...problem.tags], languages: LANGUAGES.map(language => language.id),
    submission_count: 0, accepted_count: 0, solved: false,
    visibility: 'PUBLIC', status: 'PUBLISHED', canLinkInForum: true, questionCount: 0,
  };
}

export function loadAnalysisProblems() {
  return [0,1,2,3,4,5,6].flatMap(level => {
    const catalog = JSON.parse(readFileSync(new URL(`../../src/main/resources/problems/level${level}.json`, import.meta.url), 'utf8'));
    return catalog.problems.map(publicProblem);
  });
}

function matchingProblems(problems, params) {
  const search = (params.get('search') || '').trim().toLowerCase();
  const difficulties = params.getAll('difficulty');
  const filtered = problems.filter(problem =>
    (!search || problem.problem_title.toLowerCase().includes(search) || problem.tags.some(tag => tag.toLowerCase().includes(search)) || String(problem.problem_number) === search) &&
    (!difficulties.length || difficulties.includes(`${problem.rank_type}:${problem.rank_int}`)) &&
    params.get('solved') !== 'solved');
  if (params.get('sort') === 'submits') {
    filtered.sort((a, b) => b.submission_count - a.submission_count || a.problem_number - b.problem_number);
  } else {
    filtered.sort((a, b) => a.problem_number - b.problem_number);
  }
  return filtered;
}

function problemPage(problems, params) {
  const filtered=matchingProblems(problems,params);
  const requested = Number(params.get('page'));
  const totalPages = Math.ceil(filtered.length / 30);
  const page = Math.min(Number.isSafeInteger(requested) && requested >= 0 ? requested : 0, Math.max(0,totalPages-1));
  const items = filtered.slice(page*30,(page+1)*30);
  return params.get('pagination') === 'true' ? {items,page,pageSize:30,totalItems:filtered.length,totalPages} : items;
}

export function createAnalysisMiddleware(problems = loadAnalysisProblems()) {
  const byNumber = new Map(problems.map(problem => [String(problem.problem_number),problem]));
  return (request,response,next) => {
    const url = new URL(request.url, 'http://localhost');
    const path = url.pathname;
    const send = (status,data,message) => {
      response.writeHead(status, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
      response.end(JSON.stringify(status < 400 ? {success:true,data} : {success:false,message}));
    };
    if (request.method === 'GET' && STATIC_FILES.has(path)) {
      response.writeHead(200, {'Content-Type':'text/javascript; charset=utf-8','Cache-Control':'no-store'});
      response.end(readFileSync(new URL(`../../src/main/resources/static${path}`, import.meta.url)));
      return;
    }
    if (path !== '/api' && !path.startsWith('/api/')) return next();
    // Consume but do not store submitted code, credentials, or other request bodies.
    request.resume();
    if (request.method === 'POST' && ['/api/auth/reissue','/api/analysis/session'].includes(path)) return send(200,{accessToken:TOKEN});
    if (request.method === 'POST' && path === '/api/auth/logout') return send(200,null);
    if (request.method !== 'GET') return send(409,null,'로컬 분석 모드에서는 코드를 실행·제출하거나 서버에 저장하지 않습니다. 실제 채점은 백엔드와 채점 워커를 실행한 뒤 이용해 주세요.');
    if (path === '/api/languages') return send(200,LANGUAGES);
    if (path === '/api/terms/current') return send(200,{version:'2026-11-20',effectiveDate:null,operator:'cobweb 개발팀',contactEmail:'cobweb2026.gbsw@gmail.com'});
    if (path === '/api/problems/permissions') return send(200,{canCreate:false,canManage:false});
    if (path === '/api/problems') return send(200,problemPage(problems,url.searchParams));
    if (path === '/api/problems/chapter') {
      const tag = `Lv.${url.searchParams.get('level')}-${url.searchParams.get('chapter')}`;
      return send(200,problems.filter(p=>p.tags.includes(tag)).map(({problem_number,problem_title,rank_type,rank_int,reward_points})=>
        ({problem_number,problem_title,rank_type,rank_int,reward_points})));
    }
    if (path === '/api/problems/next') {
      const params=url.searchParams,anchor=byNumber.get(params.get('number'));
      if(!anchor)return send(404,null,'문제를 찾을 수 없습니다.');
      const chapter=params.get('chapter')==='true';
      const tag=anchor.tags.filter(name=>/^Lv[.][0-9]+-[0-9]+$/.test(name)).sort()[0];
      const candidates=chapter?problems.filter(p=>tag?p.tags.includes(tag):p.rank_type===anchor.rank_type):problems;
      const ordered=matchingProblems(candidates,chapter?new URLSearchParams():params);
      const popular=!chapter&&params.get('sort')==='submits';
      const index=ordered.findIndex(p=>popular&&p.submission_count!==anchor.submission_count
        ?p.submission_count<anchor.submission_count:p.problem_number>anchor.problem_number);
      return send(200,index<0?null:{number:ordered[index].problem_number,page:Math.floor(index/30)});
    }
    if (path === '/api/problems/mine') return send(200,problemPage([],url.searchParams));
    if (path.startsWith('/api/problems/') && byNumber.has(path.slice('/api/problems/'.length))) return send(200,byNumber.get(path.slice('/api/problems/'.length)));
    if (path === '/api/me') {
      if (request.headers.authorization !== `Bearer ${TOKEN}`) return send(401,null,'분석 계정으로 계속하기를 눌러 주세요.');
      return send(200,PROFILE);
    }
    if (path === '/api/community/posts') return send(200,{items:[],total:0,page:0});
    if (path === '/api/submissions/history') return send(200,{items:[],page:0,totalPages:0,matches:0,total:0,accepted:0,pending:0,languages:[]});
    if (path === '/api/submissions/history/export') return send(200,[]);
    if (['/api/submissions','/api/groups','/api/groups/mine','/api/ranking'].includes(path)) return send(200,[]);
    // Never fall through to a real backend, including unsupported mutations.
    return send(404,null,'로컬 분석 모드에서 제공하지 않는 자료입니다.');
  };
}

export function analysisPreviewPlugin() {
  return {
    name: 'cobweb-local-analysis',
    apply: (_config,{command,mode}) => command === 'serve' && mode === 'analysis',
    config() {
      return {server:{host:'127.0.0.1',port:5174,strictPort:true,proxy:{}}};
    },
    configResolved(config) {
      if (config.server.host !== '127.0.0.1') throw new Error('분석 모드는 127.0.0.1에서만 실행할 수 있습니다.');
      config.server.proxy = {};
      config.server.cors = false;
      config.server.allowedHosts = [];
      // Prevent Vite's /@fs endpoint from exposing the private seed catalogs.
      config.server.fs.deny.push('**/src/main/resources/problems/**');
    },
    configureServer(server) {
      server.middlewares.use(createAnalysisMiddleware());
    },
  };
}
