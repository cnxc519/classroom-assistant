(function(root){
  'use strict';
  const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const md=root.markdownit({html:false,breaks:true,linkify:false,typographer:false});
  function math(source,display){
    if(source.length>16000)return '<span class="math-fallback">'+escape(source)+'</span>';
    try{return root.katex.renderToString(source,{displayMode:display,throwOnError:false,trust:false,strict:'ignore',maxExpand:1000,maxSize:12,output:'htmlAndMathml'});}
    catch(error){return '<span class="math-fallback">'+escape(source)+'</span>';}
  }
  function escapedAt(text,pos){let count=0;for(let i=pos-1;i>=0&&text[i]==='\\';i--)count++;return count%2===1;}
  md.inline.ruler.before('escape','classroom_math',function(state,silent){
    const start=state.pos,src=state.src;
    let open,close,display=false;
    if(src.startsWith('\\(',start)){open='\\(';close='\\)';}
    else if(src.startsWith('\\[',start)){open='\\[';close='\\]';display=true;}
    else if(src.startsWith('$$',start)){open='$$';close='$$';display=true;}
    else if(src[start]==='$'&&!escapedAt(src,start)&&!/\s/.test(src[start+1]||' ')){open='$';close='$';}
    else return false;
    let end=start+open.length;
    for(;;){end=src.indexOf(close,end);if(end<0)return false;
      if(escapedAt(src,end)){end+=close.length;continue;}
      if(open==='$'&&(/\s/.test(src[end-1]||' ')||/\d/.test(src[end+1]||''))){end++;continue;}
      break;
    }
    const value=src.slice(start+open.length,end);
    if(!value.trim()||(!display&&value.includes('\n')))return false;
    if(!silent){const token=state.push('html_inline','',0);token.content=math(value,display);}
    state.pos=end+close.length;return true;
  });
  md.block.ruler.before('fence','classroom_math_block',function(state,startLine,endLine,silent){
    const line=state.src.slice(state.bMarks[startLine]+state.tShift[startLine],state.eMarks[startLine]);
    let open,close;
    if(line.startsWith('$$')){open='$$';close='$$';}
    else if(line.startsWith('\\[')){open='\\[';close='\\]';}
    else return false;
    const start=state.bMarks[startLine]+state.tShift[startLine]+open.length;
    let end=state.src.indexOf(close,start);
    if(end<0)return false;
    let next=startLine;
    while(next<endLine&&state.eMarks[next]<end+close.length)next++;
    if(next>=endLine||state.src.slice(end+close.length,state.eMarks[next]).trim())return false;
    if(silent)return true;
    const token=state.push('html_block','',0);token.content=math(state.src.slice(start,end).trim(),true)+'\n';
    token.map=[startLine,next+1];state.line=next+1;return true;
  },{alt:['paragraph','reference','blockquote','list']});
  // Rendering never fetches a model-supplied image or opens a model-supplied URL.
  md.renderer.rules.image=(tokens,index)=>escape(tokens[index].content||'');
  md.renderer.rules.link_open=()=>'<span class="link-label">';md.renderer.rules.link_close=()=>'</span>';
  root.ClassroomRenderer={html:text=>md.render(String(text).slice(0,50000)),math};
  root.renderAnswer=function(text){
    const element=root.document.getElementById('answer');element.innerHTML=root.ClassroomRenderer.html(text);
    return Math.max(root.document.body.scrollHeight,element.scrollHeight);
  };
})(typeof window==='object'?window:globalThis);
