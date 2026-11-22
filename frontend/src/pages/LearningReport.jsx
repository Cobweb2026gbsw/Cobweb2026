import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import Icon from "../components/Icon";
import { useAuth } from "../context/AuthContext";
import { api, isStale } from "../lib/api";
import { formatServerDate } from "../lib/date";
import "./common.css";
import "./LearningReport.css";

const REPORTS_API = "/api/learning-reports";
const BATCH_SIZE = 5;
const BATCHES_PER_VISIT = 3;
// 서버의 120초 예약 시간이 지난 상태까지 확인할 수 있도록 여유를 둡니다.
const MAX_STATUS_CHECKS = 70;
const STATUS_INTERVAL_MS = 2000;
const DAYS_TO_SHOW = 365;
const DAY_MS = 86_400_000;
const KOREA = "Asia/Seoul";
const KOREAN_DAY = new Intl.DateTimeFormat("en-CA", {
  timeZone: KOREA, year: "numeric", month: "2-digit", day: "2-digit",
});
const DATE_LABEL = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "UTC", year: "numeric", month: "long", day: "numeric",
});
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const LEVELS = ["0개", "1개", "2~3개", "4~5개", "6개 이상"];
const ERROR_PRACTICE = {
  WRONG_ANSWER: ["오답", "최솟값·최댓값을 입력했을 때의 기대 결과를 적고 코드 테스트로 확인해 보세요."],
  COMPILATION_ERROR: ["컴파일 오류", "제출 전 같은 언어로 예제를 실행하고 첫 번째 컴파일 메시지부터 확인해 보세요."],
  RUNTIME_ERROR: ["실행 오류", "허용되는 가장 작은 입력으로 실행해 배열 접근과 나눗셈 조건을 확인해 보세요."],
  TIME_LIMIT_EXCEEDED: ["시간 초과", "최대 입력 크기에서 반복문이 몇 번 실행될지 먼저 계산해 보세요."],
  MEMORY_LIMIT_EXCEEDED: ["메모리 초과", "가장 큰 입력에서 동시에 저장할 배열의 크기와 원소 수를 적어 보세요."],
};
const AXES = [
  { key: "firstPassAccuracy", label: "첫 제출 정확도", short: "정확도", detail: "첫 제출에 정답을 받은 비율" },
  { key: "errorRecovery", label: "오류 회복", short: "오류 회복", detail: "오답이나 실행 오류 이후 정답을 받기까지의 시도" },
  { key: "limitRecovery", label: "제한 대응", short: "제한 대응", detail: "시간 또는 메모리 제한을 만난 이후의 풀이" },
  { key: "difficultyChallenge", label: "난이도 도전", short: "난이도", detail: "이번 회차에 해결한 문제의 난이도" },
  { key: "topicVariety", label: "주제 다양성", short: "다양성", detail: "이번 회차에 연습한 문제 주제의 범위" },
  { key: "practiceConsistency", label: "풀이 꾸준함", short: "꾸준함", detail: "최근에 문제를 해결한 날짜의 분포" },
];
const STATUS_MESSAGES = {
  running: "새 리포트를 만들고 있어요.",
  unavailable: "지금은 새 리포트를 만들 수 없어요.",
  paused: "새 리포트 생성이 일시 중지됐어요. 기존 리포트는 계속 볼 수 있어요.",
  retry_later: "리포트 생성이 지연됐어요. 잠시 후 다시 들어와 확인해 주세요.",
  checking_delayed: "리포트 상태를 확인하는 데 시간이 걸리고 있어요. 잠시 후 페이지를 다시 열어 확인해 주세요.",
};

function waitForStatus(signal) {
  return new Promise(resolve => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      resolve();
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, STATUS_INTERVAL_MS);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

async function loadReports(signal, publish) {
  const options = { signal };
  const initial = await api(REPORTS_API, "GET", null, options);
  if (signal.aborted) return;
  publish(initial);

  let current = initial;
  let generationRequests = 0;
  let statusChecks = 0;
  while (!signal.aborted) {
    if (current.generationState === "running") {
      if (statusChecks >= MAX_STATUS_CHECKS) {
        publish({ ...current, generationState: "checking_delayed" });
        return;
      }
      ++statusChecks;
      await waitForStatus(signal);
      if (signal.aborted) return;
      current = await api(REPORTS_API, "GET", null, options);
    } else if (current.generationState === "idle" && current.availableBatches > 0 &&
        generationRequests < BATCHES_PER_VISIT) {
      ++generationRequests;
      const result = await api(REPORTS_API + "/generate", "POST", null, options);
      if (signal.aborted) return;
      current = { ...current, generationState: result.generationState };
      // 완료가 매우 빠르거나 다른 탭에서 먼저 처리했다면 최신 회차를 다시 읽습니다.
      if (current.generationState === "idle") current = await api(REPORTS_API, "GET", null, options);
    } else {
      return;
    }
    if (!signal.aborted) publish(current);
  }
}

function koreanDay(now) {
  const pieces = Object.fromEntries(KOREAN_DAY.formatToParts(now).map(({ type, value }) => [type, value]));
  return Date.UTC(Number(pieces.year), Number(pieces.month) - 1, Number(pieces.day));
}

function activityDays(activity, now = new Date()) {
  const counts = new Map(activity.map(({ date, solvedCount }) => [date, solvedCount]));
  const oldest = koreanDay(now) - (DAYS_TO_SHOW - 1) * DAY_MS;
  const leadingDays = new Date(oldest).getUTCDay();
  const days = Array.from({ length: DAYS_TO_SHOW }, (_, index) => {
    const date = new Date(oldest + index * DAY_MS);
    const key = date.toISOString().slice(0, 10);
    return { key, date, solved: counts.get(key) ?? 0 };
  });
  const months = new Map();
  days.forEach(({ date }, index) => {
    if (index === 0 || date.getUTCDate() === 1) {
      const week = Math.floor((leadingDays + index) / 7) + 1;
      months.set(week, date.getUTCMonth() + 1);
    }
  });
  return { days, leadingDays, months, weeks: Math.ceil((leadingDays + days.length) / 7) };
}

function activityLevel(count) {
  if (count === 0) return 0;
  if (count === 1) return 1;
  if (count < 4) return 2;
  return count < 6 ? 3 : 4;
}

function ActivityCalendar({ activity }) {
  const { days, leadingDays, months, weeks } = activityDays(activity);
  const dayGrid = useRef(null);
  const scrollArea = useRef(null);
  const titleId = useId();
  const keyHelpId = useId();
  const [selected, setSelected] = useState(DAYS_TO_SHOW - 1);
  const [hovered, setHovered] = useState(null);
  const current = days[hovered ?? selected];
  const countLabel = day => `${DATE_LABEL.format(day.date)} · ${day.solved}문제 해결`;
  const solved = days.reduce((total, day) => total + day.solved, 0);
  const activeDays = days.filter(day => day.solved > 0).length;

  useEffect(() => {
    const calendar = scrollArea.current;
    if (calendar) calendar.scrollLeft = calendar.scrollWidth;
  }, []);

  function move(event, index) {
    const offset = { ArrowUp: -1, ArrowDown: 1, ArrowLeft: -7, ArrowRight: 7 }[event.key];
    let next = index;
    if (typeof offset === "number") next = index + offset;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = days.length - 1;
    else return;
    event.preventDefault();
    setHovered(null);
    next = Math.min(days.length - 1, Math.max(0, next));
    dayGrid.current?.querySelector(`[data-day-index="${next}"]`)?.focus();
  }

  return <section className="cw-card lr-activity" aria-labelledby={titleId}>
    <header className="lr-card-head">
      <div><h2 className="cw-card-title" id={titleId}>풀이 잔디</h2><p>최근 1년 · 한국시간</p></div>
      <dl className="lr-activity-totals">
        <div><dt>해결한 문제</dt><dd>{solved.toLocaleString("ko-KR")}<small>문제</small></dd></div>
        <div><dt>풀이한 날</dt><dd>{activeDays.toLocaleString("ko-KR")}<small>일</small></dd></div>
      </dl>
    </header>
    <p className="lr-screen-reader" id={keyHelpId}>방향키로 날짜를 이동하고 Home과 End로 첫날과 오늘을 선택할 수 있습니다.</p>
    <div className="lr-calendar">
      <div className="lr-weekdays" aria-hidden="true">{WEEKDAYS.map(day => <span key={day}>{day}</span>)}</div>
      <div className="lr-calendar-scroll" ref={scrollArea}>
        <div className="lr-months" aria-hidden="true" style={{ "--lr-weeks": weeks }}>
          {Array.from(months, ([week, month]) => <span key={week} style={{ gridColumn: week }}>{month}월</span>)}
        </div>
        <div className="lr-day-grid" role="group" aria-label="날짜별로 처음 맞힌 문제" aria-describedby={keyHelpId} ref={dayGrid}
          onMouseLeave={() => setHovered(null)}>
          {Array.from({ length: leadingDays }, (_, index) => <span key={index} aria-hidden="true" />)}
          {days.map((day, index) => <button type="button" key={day.key} data-day-index={index}
            className={`lr-day lr-level-${activityLevel(day.solved)}`}
            aria-label={countLabel(day)} aria-pressed={index === selected} tabIndex={index === selected ? 0 : -1}
            onFocus={() => { setSelected(index); setHovered(null); }} onMouseEnter={() => setHovered(index)}
            onKeyDown={event => move(event, index)} onClick={() => { setSelected(index); setHovered(null); }} />)}
        </div>
      </div>
    </div>
    <div className="lr-calendar-footer">
      <span className="lr-selected-date">{countLabel(current)}</span>
      <ul className="lr-legend" aria-label="하루 해결 문제 수의 색상 기준">
        {LEVELS.map((label, index) => <li key={label}><i aria-hidden="true" className={`lr-level-${index}`} />{label}</li>)}
      </ul>
    </div>
  </section>;
}

function isScore(value) {
  return Number.isFinite(value) && value >= 0 && value <= 100;
}

function radarPoint(index, radius) {
  const radians = index * Math.PI / 3 - Math.PI / 2;
  return [50 + Math.cos(radians) * radius, 50 + Math.sin(radians) * radius];
}

function radarLine([x, y], [endX, endY]) {
  return {
    left: `${x}%`, top: `${y}%`,
    width: `${Math.hypot(endX - x, endY - y)}%`,
    transform: `rotate(${Math.atan2(endY - y, endX - x)}rad)`,
  };
}

function radarPosition([x, y]) {
  return { left: `${x}%`, top: `${y}%` };
}

function RadarChart({ values, active }) {
  const plotRadius = 33;
  const dots = values.map((value, index) => isScore(value) ? radarPoint(index, value * plotRadius / 100) : null);
  // 빠진 값이 있으면 해당 꼭짓점은 채우지 않고 측정된 이웃 축끼리만 연결합니다.
  const filledArea = dots.every(Boolean) ? `polygon(${dots.map(([x, y]) => `${x}% ${y}%`).join(",")})` : null;
  return <div className="lr-radar" aria-hidden="true">
    {[25, 50, 75, 100].flatMap(level => AXES.map(({ key }, index) => <i
      key={`${level}-${key}`} className="lr-radar-line"
      style={radarLine(radarPoint(index, level * plotRadius / 100), radarPoint(index + 1, level * plotRadius / 100))} />))}
    {AXES.map(({ key }, index) => <i key={key} className={`lr-radar-line${index === active ? " highlighted" : ""}`}
      style={radarLine([50, 50], radarPoint(index, plotRadius))} />)}
    {filledArea && <div className="lr-radar-fill" style={{ clipPath: filledArea }} />}
    {dots.map((point, index) => {
      const next = dots[(index + 1) % dots.length];
      return point && next && <i key={index} className="lr-radar-line result" style={radarLine(point, next)} />;
    })}
    {dots.map((point, index) => point && <i key={index} className={`lr-radar-dot${index === active ? " highlighted" : ""}`}
      style={radarPosition(point)} />)}
    {AXES.map(({ key, short }, index) => <span key={key} className={`lr-radar-axis${index === active ? " highlighted" : ""}`}
      style={radarPosition(radarPoint(index, 44))}>{short}</span>)}
  </div>;
}

function ReportCard({ report, previous }) {
  const [active, setActive] = useState(0);
  const values = AXES.map(({ key }) => report.radar?.[key] ?? null);
  const focusId = useId();
  const titleId = useId();
  const currentRate = report.radar?.firstPassAccuracy;
  const previousRate = previous?.radar?.firstPassAccuracy;
  const comparable = isScore(currentRate) && isScore(previousRate);
  const evidence = report.evidence;
  const practice = ERROR_PRACTICE[evidence?.mostFrequentError];
  return <section className="cw-card lr-report-card" aria-labelledby={titleId}>
    <header className="lr-card-head">
      <div><h3 className="cw-card-title" id={titleId}>{report.reportNumber}회차 리포트</h3>
        <p>{formatServerDate(report.createdAt, { dateStyle: "long", timeZone: KOREA })} · {report.problemIds.length}문제</p></div>
      <div className="lr-score"><span>해결능력 점수</span>
        <strong>{isScore(report.problemSolvingScore) ? report.problemSolvingScore : "—"}<small>/ 100</small></strong></div>
    </header>
    <div className="lr-report-body">
      <RadarChart values={values} active={active} />
      <div className="lr-score-breakdown">
        <h4>역량별 점수</h4>
        <ul>{AXES.map(({ key, label }, index) => <li key={key}>
          <button type="button" aria-pressed={index === active} aria-controls={focusId} onClick={() => setActive(index)}>
            <span>{label}</span><strong>{isScore(values[index]) ? `${values[index]}점` : "측정 기록 없음"}</strong>
          </button>
        </li>)}</ul>
        <p id={focusId} aria-live="polite">{AXES[active].detail}</p>
      </div>
    </div>
    <div className="lr-summary">
      <h4>AI 요약</h4><p>{report.userSummary}</p>
    </div>
    {(evidence?.observedSubmissionCount > 0 || practice || comparable) && <section className="lr-evidence">
      <h4>이번 회차의 풀이 기록</h4>
      {evidence?.observedSubmissionCount > 0 && <p>첫 제출 결과를 확인할 수 있는 {evidence.observedSubmissionCount}문제 중 <strong>{evidence.firstPassCount}문제</strong>를 한 번에 맞혔어요.</p>}
      {comparable && <p>첫 제출 정답률 <strong>{previousRate}% → {currentRate}%</strong>
        {' · '}{currentRate === previousRate ? '직전 회차와 같아요.' :
          `직전 회차보다 ${Math.abs(currentRate - previousRate)}%p ${currentRate > previousRate ? '높아요.' : '낮아요.'}`}</p>}
      {practice && <p><strong>{practice[0]} {evidence.errorCount}회</strong> · 첫 정답을 받기 전 자주 나온 채점 결과예요. {practice[1]}</p>}
    </section>}
  </section>;
}

function RecommendedPractice({ items }) {
  const titleId = useId();
  return <section className="cw-card lr-practice" aria-labelledby={titleId}>
    <h2 className="cw-card-title" id={titleId}>다음 연습 문제</h2>
    {items.length ? <>
      <p>최근 회차에서 연습한 주제와 겹치는 미해결 문제예요.</p>
      <ul>{items.map(item => <li key={item.problemNumber}><Link to={'/problems/' + item.problemNumber}>
        <span className="lr-practice-title"><span className="cw-mono">{item.problemNumber}</span> {item.title}</span>
        <span className="lr-practice-meta">{item.sharedTopic} · {item.rankType} {item.rankInt}</span>
      </Link></li>)}</ul>
    </> : <p>이번 회차와 주제가 겹치는 미해결 공개 문제가 없습니다. <Link className="cw-text-link" to="/problems">전체 문제 보기</Link></p>}
  </section>;
}

function LearningReportSession() {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [selectedReport, setSelectedReport] = useState(null);
  const historyTitleId = useId();
  useEffect(() => {
    const request = new AbortController();
    loadReports(request.signal, setData).catch(failure => {
      if (!request.signal.aborted && !isStale(failure)) setError(failure.message);
    });
    return () => request.abort();
  }, []);

  if (!data) return error ? <p className="lr-notice" role="alert">{error}</p> :
    <p className="lr-notice" role="status">풀이 기록을 불러오고 있어요.</p>;

  const reports = data.reports;
  const report = reports.find(({ reportNumber }) => reportNumber === selectedReport) ?? reports[0];
  const nextProgress = BATCH_SIZE - data.remainingToNextReport;
  const stateMessage = STATUS_MESSAGES[data.generationState];
  return <>
    <ActivityCalendar activity={data.activity} />
    <section aria-labelledby={historyTitleId}>
      <header className="lr-history-head">
        <div><h2 className="cw-card-title" id={historyTitleId}>학습 리포트</h2>
          <p>공개 연습 문제를 처음 맞힌 기록을 5문제씩 분석합니다.</p></div>
        {reports.length > 1 && <label className="lr-history-select"><span>리포트 회차</span>
          <select value={selectedReport ?? ""} onChange={event => setSelectedReport(event.target.value ? Number(event.target.value) : null)}>
            <option value="">최신 회차</option>
            {reports.map(entry => <option key={entry.reportNumber} value={entry.reportNumber}>{entry.reportNumber}회차</option>)}
          </select></label>}
      </header>
      <div className="lr-progress" aria-label="다음 리포트 진행 상황">
        {data.availableBatches > 0 && <span><strong>분석 대기 {data.availableBatches}회차</strong></span>}
        <div><span>다음 회차 <strong>{nextProgress} / {BATCH_SIZE}문제</strong></span>
          <progress value={nextProgress} max={BATCH_SIZE} aria-label="다음 회차까지 푼 문제" /></div>
      </div>
      {(error || stateMessage) && <p className="lr-notice" role={error ? "alert" : "status"}>{error || stateMessage}</p>}
      {report ? <ReportCard key={report.reportNumber} report={report}
        previous={reports.find(item => item.reportNumber === report.reportNumber - 1)} /> :
        <div className="cw-card lr-empty">
          <Icon name="gauge" /><h3>첫 리포트를 기다리고 있어요</h3>
          <p>{data.availableBatches === 0 ? `공개 연습 문제 ${data.remainingToNextReport}개를 더 맞히면 첫 제출 정답률과 풀이 피드백을 확인할 수 있어요.` : "첫 번째 학습 리포트가 완성되면 여기에 표시돼요."}</p>
          {data.availableBatches === 0 && <Link className="cw-btn sm" to="/problems">문제 풀기</Link>}
        </div>}
    </section>
    {report && report.reportNumber === reports[0].reportNumber && Array.isArray(data.recommendations) &&
      <RecommendedPractice items={data.recommendations} />}
  </>;
}

export default function LearningReport({ dark }) {
  const { user, epoch } = useAuth();
  return <main className={`cw-page lr-page ${dark ? "dark" : "light"}`}><div className="cw-wrap">
    <header className="cw-head">
      <div><Link className="cw-text-link lr-back" to="/dashboard"><span aria-hidden="true">←</span> 내 활동</Link>
        <h1 className="cw-title">AI 리포트</h1>
        <p className="cw-desc">풀이 기록을 돌아보고 다음에 연습할 내용을 확인하세요.</p></div>
      <Link className="cw-btn primary" to="/problems">문제 풀기</Link>
    </header>
    {user && <LearningReportSession key={epoch} />}
  </div></main>;
}
