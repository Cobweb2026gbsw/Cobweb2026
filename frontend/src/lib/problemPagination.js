export const PROBLEM_PAGE_SIZE=30;
export const PAGE_BUTTON_COUNT=6;

// URL에는 0-based 정수만 보내며, 서버 int 범위를 벗어난 값은 첫 페이지로 되돌립니다.
export function requestedProblemPage(value){
  const number=Number(value);
  return Number.isSafeInteger(number)&&number>=0&&number<=2147483647?number:0;
}

export function problemPageData(data,requestedPage){
  // 순차 배포 중 구형 서버가 반환하는 배열도 읽을 수 있지만 전체 개수를 추정해 표시하지는 않습니다.
  if(Array.isArray(data))return data.length<=PROBLEM_PAGE_SIZE?{items:data,page:requestedPage,pageSize:PROBLEM_PAGE_SIZE,totalItems:null,totalPages:requestedPage+1+(data.length===PROBLEM_PAGE_SIZE?1:0),legacy:true}:null;
  if(!data||!Array.isArray(data.items)||data.pageSize!==PROBLEM_PAGE_SIZE)return null;
  const {page,totalItems,totalPages,items}=data;
  if(![page,totalItems,totalPages].every(value=>Number.isSafeInteger(value)&&value>=0)||totalPages!==Math.ceil(totalItems/PROBLEM_PAGE_SIZE))return null;
  if(page>Math.max(0,totalPages-1)||items.length!==Math.min(PROBLEM_PAGE_SIZE,Math.max(0,totalItems-page*PROBLEM_PAGE_SIZE)))return null;
  return {items,page,pageSize:PROBLEM_PAGE_SIZE,totalItems,totalPages,legacy:false};
}

// 번호는 여섯 개씩 묶습니다. 다음/이전은 한 페이지씩 이동하고 묶음 경계에서 번호가 바뀝니다.
export function problemPageButtons(page,totalPages){
  const total=Math.max(1,totalPages),current=Math.min(Math.max(0,page),total-1);
  const first=Math.floor(current/PAGE_BUTTON_COUNT)*PAGE_BUTTON_COUNT;
  return Array.from({length:Math.min(PAGE_BUTTON_COUNT,total-first)},(_,index)=>first+index);
}
