import {Component} from 'react';
import './RenderErrorBoundary.css';

/** 비동기 요청은 API 경계에서 처리하고, 이 경계는 React 렌더/lifecycle 오류만 격리합니다. */
export default class RenderErrorBoundary extends Component {
  state={failed:false};
  static getDerivedStateFromError(){return {failed:true};}
  render(){
    if(!this.state.failed)return this.props.children;
    // 오류 객체·stack·기존 초안/서버 자료를 state나 fallback DOM에 보관하지 않습니다.
    return <main className={`cw-page cw-render-error ${this.props.dark===false?'light':'dark'}`}>
      <section className="cw-card" role="alert" aria-labelledby="render-error-title">
        <h1 id="render-error-title" className="cw-card-title">화면을 표시하지 못했습니다.</h1>
        <p>화면의 임시 입력은 초기화됐습니다. 다시 열어도 제출·저장 요청을 자동으로 재전송하지 않습니다.</p>
        <p>직전에 저장하거나 제출했다면 기록을 확인한 뒤 다시 진행해주세요.</p>
        <div className="cw-actions"><button type="button" className="cw-btn primary" onClick={()=>this.setState({failed:false})}>화면 다시 열기</button><a className="cw-btn" href="/">홈으로</a></div>
      </section>
    </main>;
  }
}
