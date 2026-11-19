import { useLayoutEffect, useRef, useState } from 'react';

export function useProgressiveFields(order) {
  const [step, setStep] = useState(0);
  const inputs = useRef({});
  const pendingFocus = useRef(null);

  useLayoutEffect(() => {
    if (!pendingFocus.current) return;
    inputs.current[pendingFocus.current]?.focus();
    pendingFocus.current = null;
  }, [step]);

  const reveal = (name) => {
    if (inputs.current[name]) {
      inputs.current[name].focus();
      return;
    }
    pendingFocus.current = name;
    setStep(previous => Math.max(previous, order.indexOf(name)));
  };
  const truncate = (lastStep) => {
    pendingFocus.current = null;
    setStep(lastStep);
  };
  return { step, inputs, reveal, truncate };
}

export function advanceOnEnter(event, advance) {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  // 한글 조합을 확정하는 Enter와 길게 누른 Enter는 다음 단계로 넘기지 않습니다.
  if (event.nativeEvent.isComposing || event.keyCode === 229 || event.repeat) return;
  advance();
}
