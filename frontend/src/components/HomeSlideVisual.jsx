// Abstract line graphics only; no invented rankings or submission records.
export default function HomeSlideVisual({variant}) {
  return <div className={`home-visual home-visual-${variant}`} aria-hidden="true">
    <svg viewBox="0 0 440 360" focusable="false">
      <g className="home-visual-grid" fill="none" stroke="currentColor">
        {[60,120,180,240,300,360].map(x=><path key={`x${x}`} d={`M${x} 20v320`}/>)}
        {[60,120,180,240,300].map(y=><path key={`y${y}`} d={`M20 ${y}h400`}/>)}
      </g>
      {variant==='start'&&<>
        <g className="home-web" fill="none" stroke="currentColor">
          <path d="m220 30 130 75v150l-130 75-130-75V105Zm0 38 97 56v112l-97 56-97-56V124Zm0 38 64 37v74l-64 37-64-37v-74Z"/>
          <path d="M220 30v300M90 105l260 150M90 255l260-150"/>
        </g>
        <rect className="home-visual-core" x="147" y="126" width="146" height="108" rx="18"/>
        <path className="home-visual-symbol" d="m190 159-22 21 22 21m60-42 22 21-22 21m-20-51-20 60"/>
        <g className="home-visual-nodes"><circle cx="220" cy="30" r="5"/><circle cx="350" cy="255" r="5"/><circle cx="90" cy="255" r="5"/></g>
      </>}
      {variant==='activity'&&<>
        <path className="home-visual-connector" d="M82 105h80l85 150h104M82 255h80l85-150h104"/>
        <g className="home-visual-panels"><rect x="47" y="70" width="70" height="70" rx="16"/><rect x="323" y="220" width="70" height="70" rx="16"/><rect x="160" y="120" width="120" height="120" rx="22"/></g>
        <g className="home-visual-symbol"><path d="M184 155h71m-71 24h50m-50 24h34M70 94l12 11-12 11m19 0h9m246 140 9 9 19-22"/></g>
        <g className="home-visual-nodes"><circle cx="82" cy="255" r="6"/><circle cx="358" cy="105" r="6"/></g>
      </>}
      {variant==='ranking'&&<>
        <g className="home-visual-bars"><rect x="64" y="242" width="56" height="62" rx="4"/><rect x="148" y="200" width="56" height="104" rx="4"/><rect x="232" y="144" width="56" height="160" rx="4"/><rect x="316" y="76" width="56" height="228" rx="4"/></g>
        <path className="home-visual-symbol" d="m82 207 96-68 80-40 88-63m-30 0h30v30"/>
        <g className="home-visual-nodes"><circle cx="82" cy="207" r="5"/><circle cx="178" cy="139" r="5"/><circle cx="258" cy="99" r="5"/></g>
      </>}
      {variant==='community'&&<>
        <path className="home-visual-connector" d="m220 180-118-87m118 87 123-63m-123 63-99 94m99-94 104 98M102 93l19 181m222-157-19 161"/>
        <g className="home-visual-panels"><circle cx="102" cy="93" r="31"/><circle cx="343" cy="117" r="24"/><circle cx="121" cy="274" r="24"/><circle cx="324" cy="278" r="31"/><rect x="161" y="133" width="118" height="94" rx="23"/></g>
        <g className="home-visual-nodes"><circle cx="193" cy="180" r="4"/><circle cx="220" cy="180" r="4"/><circle cx="247" cy="180" r="4"/></g>
        <g className="home-visual-symbol"><path d="M92 93h20m-10-10v20m212 175h20m-10-10v20"/></g>
      </>}
    </svg>
    <span className="home-visual-caption">{({start:'THINK · CODE · SOLVE',activity:'WRITE · SUBMIT · REVIEW',ranking:'YOUR NEXT CHALLENGE',community:'CONNECT THROUGH CODE'})[variant]}</span>
  </div>;
}
