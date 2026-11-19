import {Link} from 'react-router-dom';
import {RANKS} from '../lib/problemModel';
import {RANK_NAMES,rankProblemsUrl} from '../lib/problemNavigation';
import Icon from '../components/Icon';
import './common.css';
import './Steps.css';

export default function Steps({dark}){
  return <main className={'cw-page st-page '+(dark?'dark':'light')}><div className="cw-wrap st-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">DIFFICULTY</p><h1 className="cw-title">난이도별 문제</h1><p className="cw-desc">처음이라면 입문부터 시작해 보세요. 아래로 갈수록 어려워지며, 같은 등급에서는 5 → 1 순서로 난도가 높아집니다.</p></div><Link className="cw-btn" to="/problems">전체 문제</Link></header>
    <ol className="st-list">{RANKS.map((rank,index)=><li className="cw-card st-step" key={rank}>
      <Link className="st-head" to={rankProblemsUrl(rank)}><span className="st-no">{index+1}</span><span className="st-info"><strong>{RANK_NAMES[rank]}</strong><small>{rank} · 5단계부터 1단계까지</small></span><span className="st-progress">이 난이도 문제 보기</span><Icon name="chevronR"/></Link>
    </li>)}</ol>
    <p className="cw-hint st-help">난이도를 선택한 다음 풀이 여부와 세부 단계를 좁힐 수 있어요.</p>
  </div></main>;
}
