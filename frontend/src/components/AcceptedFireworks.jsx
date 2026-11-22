import {useEffect, useState} from 'react';
import {createPortal} from 'react-dom';
import './AcceptedFireworks.css';

const bursts = [
  {left: '20%', top: '36%', delay: 0},
  {left: '80%', top: '29%', delay: 160},
  {left: '51%', top: '21%', delay: 330},
  {left: '32%', top: '67%', delay: 480},
  {left: '73%', top: '61%', delay: 640},
];
const sparkCount = 24;
const sparkDuration = 1180;
const showDuration = bursts[bursts.length - 1].delay + sparkDuration;

export default function AcceptedFireworks({dark}) {
  const [visible, setVisible] = useState(() => !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    if (!visible) return;
    const timer = window.setTimeout(() => setVisible(false), showDuration);
    return () => window.clearTimeout(timer);
  }, [visible]);

  if (!visible) return null;
  return createPortal(
    <div className={`accepted-fireworks ${dark ? 'dark' : 'light'}`} aria-hidden="true"
      style={{'--spark-duration': sparkDuration + 'ms'}}>
      {bursts.map(({left, top, delay}, burst) => (
        <div key={burst} className="accepted-fireworks-burst" style={{left, top, '--delay': delay + 'ms'}}>
          {Array.from({length: sparkCount}, (_, i) => (
            <i key={i} style={{
              '--angle': (i * 360 / sparkCount + burst * 8) + 'deg',
              '--reach': (i % 2 ? -0.64 : -1) * (0.88 + (i % 5) * 0.04),
              '--spark-color': `var(--spark-${(i + burst) % 3})`,
            }}/>
          ))}
        </div>
      ))}
    </div>,
    document.body
  );
}
