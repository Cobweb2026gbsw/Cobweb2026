import {useEffect,useRef,useState} from 'react';

const MOTION_QUERY='(prefers-reduced-motion: reduce)';
export const HOME_SLIDE_INTERVAL=3000;

export default function useHomeCarousel(count) {
  const [frame,setFrame]=useState({position:0,animated:false});
  const [focused,setFocused]=useState(false);
  const [reducedMotion,setReducedMotion]=useState(()=>window.matchMedia?.(MOTION_QUERY).matches??false);
  const [visible,setVisible]=useState(()=>document.visibilityState!=='hidden'),[inView,setInView]=useState(true);
  const viewportRef=useRef(null);
  const activeIndex=frame.position%count,playing=!reducedMotion&&!focused&&visible&&inView;
  useEffect(()=>{
    const media=window.matchMedia?.(MOTION_QUERY);
    const motion=event=>setReducedMotion(event.matches);
    const visibility=()=>setVisible(document.visibilityState!=='hidden');
    media?.addEventListener?.('change',motion);
    document.addEventListener('visibilitychange',visibility);
    return()=>{media?.removeEventListener?.('change',motion);document.removeEventListener('visibilitychange',visibility);};
  },[]);
  useEffect(()=>{
    if(!window.IntersectionObserver||!viewportRef.current)return;
    const observer=new window.IntersectionObserver(([entry])=>setInView(entry.isIntersecting),{threshold:.15});
    observer.observe(viewportRef.current);return()=>observer.disconnect();
  },[]);
  useEffect(()=>{
    if(!playing)return;
    const timer=window.setInterval(()=>setFrame(current=>({position:current.position%count+1,animated:true})),HOME_SLIDE_INTERVAL);
    return()=>window.clearInterval(timer);
  },[playing,count]);
  // The first slide is repeated after the last for a continuous forward loop.
  // Also reset when transitionend is interrupted or motion settings change.
  useEffect(()=>{
    if(frame.position!==count)return;
    const timer=window.setTimeout(()=>setFrame(current=>current.position===count?{position:0,animated:false}:current),reducedMotion?0:700);
    return()=>window.clearTimeout(timer);
  },[frame.position,count,reducedMotion]);
  function finish(event) {
    if(event.target!==event.currentTarget||event.propertyName!=='transform')return;
    setFrame(current=>current.position===count?{position:0,animated:false}:current);
  }
  function select(index) {
    setFrame({position:((index%count)+count)%count,animated:true});
  }
  return {frame,activeIndex,playing,reducedMotion,viewportRef,setFocused,select,finish};
}
