import {useEffect,useRef} from 'react';
import './ProblemComponents.css';

export default function Markdown({children='',className=''}){
  const ref=useRef(null);
  useEffect(()=>{const node=ref.current;if(window.CobwebMarkdown)window.CobwebMarkdown.render(node,children);else node.textContent=children??'';return()=>node.replaceChildren();},[children]);
  return <div ref={ref} className={`markdown-body ${className}`}/>;
}
export function Example({example,index}){
  const ref=useRef(null);
  useEffect(()=>{const node=ref.current;if(window.CobwebMarkdown)window.CobwebMarkdown.example(node,example,index);else node.textContent=`예제 ${index+1}\n${example.input_text??example.input??''}\n${example.output_text??example.output??''}`;return()=>node.replaceChildren();},[example,index]);
  return <div ref={ref} className="cw-example"/>;
}
