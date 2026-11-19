import {useRef} from 'react';
import {Link} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {draftOwner} from '../lib/problemDraftCache';
import {rankProblemsUrl,recentProblem} from '../lib/problemNavigation';
import useHomeCarousel from '../lib/useHomeCarousel';
import HomeSlideVisual from '../components/HomeSlideVisual';
import './Main.css';

export default function Main({dark}) {
  const {user,loading}=useAuth();
  const recent=recentProblem(loading?'':draftOwner(user));
  const carousel=useHomeCarousel(4),gesture=useRef(null);
  const slides=[
    {id:'start',label:'문제 시작',eyebrow:'ONLINE JUDGE',title:'CobWeb',
      description:'내 수준에 맞는 문제를 고르고 코드로 풀어보세요.\n처음 시작한다면 입문 문제부터 차근차근 도전해 보세요.',
      action:recent?'이어서 풀기':'입문 문제 시작',path:recent?recent.url:rankProblemsUrl('INITIATE'),
      note:recent?`이 브라우저에서 마지막으로 열어본 문제 #${recent.number}`:'작은 문제 하나부터, 나만의 풀이를 쌓아가세요.'},
    {id:'activity',label:user?'내 제출':'난이도별 문제',eyebrow:user?'MY SUBMISSIONS':'FIND YOUR LEVEL',
      title:user?'내 제출':'난이도별 문제',
      description:user?'최근 제출한 코드와 채점 결과를 확인합니다.\n도전했던 문제를 다시 살펴보고, 다음 풀이를 이어가세요.':'입문부터 심화까지, 내 수준에 맞는 문제를 고릅니다.\n같은 등급에서는 5에서 1로 갈수록 어려워집니다.',
      action:user?'내 제출 확인':'난이도별 문제 보기',path:user?'/submissions':'/steps',
      note:user?'작성하고, 제출하고, 결과를 확인하세요.':'내 수준에 맞는 도전부터 시작하세요.'},
    {id:'ranking',label:'랭킹',eyebrow:'RANKING',title:'랭킹',
      description:'처음 해결한 문제의 점수를 모아 랭크를 올려 보세요.\n다른 사람들의 도전을 살펴보고, 나의 다음 목표를 정해 보세요.',
      action:'랭킹 보기',path:'/ranking',note:'한 문제씩 쌓아가는 도전의 기록.'},
    {id:'community',label:'커뮤니티',eyebrow:'COMMUNITY',title:'커뮤니티',
      description:'문제에 관해 질문하고 풀이 경험을 나눕니다.\n서로 다른 생각에서 새로운 풀이를 발견해 보세요.',
      action:'커뮤니티 둘러보기',path:'/forum',note:'질문하고, 생각을 나누고, 함께 성장하세요.'},
  ];
  const frames=[...slides,slides[0]];
  function pointerStart(event) {
    if(event.pointerType!=='touch'||event.target.closest('a,button'))return;
    gesture.current={id:event.pointerId,x:event.clientX,y:event.clientY};
  }
  function pointerEnd(event) {
    const start=gesture.current;gesture.current=null;
    if(!start||start.id!==event.pointerId)return;
    const x=event.clientX-start.x,y=event.clientY-start.y;
    if(Math.abs(x)>60&&Math.abs(x)>Math.abs(y)*1.5)carousel.select(carousel.activeIndex+(x<0?1:-1));
  }
  return <main className={`main-top home-deck ${dark?'dark':'light'}`}>
    <section className="home-carousel" aria-label="CobWeb 소개" aria-roledescription="캐러셀"
      onFocusCapture={()=>carousel.setFocused(true)}
      onBlurCapture={event=>{if(!event.currentTarget.contains(event.relatedTarget))carousel.setFocused(false);}}>
      <div ref={carousel.viewportRef} className="home-viewport" onPointerDown={pointerStart} onPointerUp={pointerEnd} onPointerCancel={()=>{gesture.current=null;}}>
        <div className="home-track" style={{transform:`translateX(-${carousel.frame.position*100}%)`,transition:carousel.frame.animated&&!carousel.reducedMotion?undefined:'none'}} onTransitionEnd={carousel.finish}>
          {frames.map((slide,index)=>{
            const visible=index===carousel.frame.position;
            return <div key={`${slide.id}-${index}`} className={`home-slide home-slide-${slide.id}`} role="group" aria-roledescription="슬라이드"
              aria-label={`${index%slides.length+1} / ${slides.length} · ${slide.label}`} aria-hidden={!visible} inert={!visible?'':undefined}>
              <div className="home-slide-copy">
                <p className="home-eyebrow">{slide.eyebrow}</p>
                {slide.id==='start'?<h1>{slide.title}</h1>:<h2>{slide.title}</h2>}
                <p className="home-description">{slide.description}</p>
                <div className="home-actions"><Link className="home-primary" to={slide.path}>{slide.action}<span aria-hidden="true">→</span></Link>
                  {slide.id==='start'&&<><Link to="/problems">전체 문제 탐색</Link><Link to="/steps">난이도별 문제</Link></>}
                </div>
                <p className="home-note">{slide.note}</p>
              </div>
              <HomeSlideVisual variant={slide.id}/>
            </div>;
          })}
        </div>
      </div>
      <p className="home-sr-only" role="status" aria-live={carousel.playing?'off':'polite'}>{carousel.activeIndex+1} / {slides.length} · {slides[carousel.activeIndex].label}</p>
    </section>
  </main>;
}
