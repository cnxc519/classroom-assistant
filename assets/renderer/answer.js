(function(root){
  'use strict';
  const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const md=root.markdownit({html:false,breaks:true,linkify:false,typographer:false});
  let pipeMarker='';
  function math(source,display){
    if(pipeMarker)source=source.split(pipeMarker).join('|');
    if(source.length>16000)return '<span class="math-fallback">'+escape(source)+'</span>';
    try{return root.katex.renderToString(source,{displayMode:display,throwOnError:false,trust:false,strict:'ignore',maxExpand:1000,maxSize:12,output:'htmlAndMathml'});}
    catch(error){return '<span class="math-fallback">'+escape(source)+'</span>';}
  }
  function escapedAt(text,pos){let count=0;for(let i=pos-1;i>=0&&text[i]==='\\';i--)count++;return count%2===1;}
  function mathSpan(src,start){
    if(escapedAt(src,start))return null;
    let open,close,display=false;
    if(src.startsWith('\\(',start)){open='\\(';close='\\)';}
    else if(src.startsWith('\\[',start)){open='\\[';close='\\]';display=true;}
    else if(src.startsWith('$$',start)){open='$$';close='$$';display=true;}
    else if(src[start]==='$'&&!escapedAt(src,start)&&!/\s/.test(src[start+1]||' ')){open='$';close='$';}
    else return null;
    let end=start+open.length;
    for(;;){end=src.indexOf(close,end);if(end<0)return null;
      if(escapedAt(src,end)){end+=close.length;continue;}
      if(open==='$'&&(/\s/.test(src[end-1]||' ')||/\d/.test(src[end+1]||''))){end++;continue;}
      break;
    }
    const value=src.slice(start+open.length,end);
    if(!value.trim()||(!display&&value.includes('\n')))return null;
    return {start:start+open.length,end,after:end+close.length,value,display};
  }
  md.inline.ruler.before('escape','classroom_math',function(state,silent){
    const span=mathSpan(state.src,state.pos);if(!span)return false;
    if(!silent){const token=state.push('html_inline','',0);token.content=math(span.value,span.display);}
    state.pos=span.after;return true;
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
  const codeFence=md.renderer.rules.fence;
  md.renderer.rules.fence=function(tokens,index,options,env,self){
    const token=tokens[index],language=token.info.trim().split(/\s/)[0].toLowerCase();
    if(!['math','latex','tex','katex'].includes(language))return codeFence(tokens,index,options,env,self);
    let source=token.content.trim();
    const span=mathSpan(source,0);if(span&&span.after===source.length)source=span.value;
    return math(source,true)+'\n';
  };
  function prepare(text){
    // Some models wrap an entire Markdown answer in one fenced block.
    const wrapper=text.match(/^\s*(`{3,}|~{3,})[ \t]*(?:markdown|md)[ \t]*\r?\n([\s\S]*?)\r?\n\1[ \t]*$/i);
    if(wrapper)text=wrapper[2];
    pipeMarker='';
    for(let code=0xe000;code<=0xe0ff;code++){const candidate=String.fromCharCode(code);if(!text.includes(candidate)){pipeMarker=candidate;break;}}
    if(!pipeMarker)return text;
    // Markdown tables split pipes before inline math is parsed. Protect math pipes only,
    // retaining their original TeX meaning (including |, \\|, and escaped table pipes).
    let fence=null;
    return text.split('\n').map(line=>{
      const boundary=line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
      if(boundary){
        if(!fence)fence={character:boundary[1][0],length:boundary[1].length};
        else if(boundary[1][0]===fence.character&&boundary[1].length>=fence.length&&!boundary[2].trim())fence=null;
        return line;
      }
      if(fence||/^ {4}|^\t/.test(line)||!line.includes('|'))return line;
      let output='',position=0;
      while(position<line.length){
        if(line[position]==='`'&&!escapedAt(line,position)){
          const ticks=line.slice(position).match(/^`+/)[0],end=line.indexOf(ticks,position+ticks.length);
          if(end>=0){output+=line.slice(position,end+ticks.length);position=end+ticks.length;continue;}
        }
        const span=mathSpan(line,position);
        if(span){output+=line.slice(position,span.start)+span.value.replace(/\|/g,pipeMarker)+line.slice(span.end,span.after);position=span.after;}
        else output+=line[position++];
      }
      return output;
    }).join('\n');
  }
  // Rendering never fetches a model-supplied image or opens a model-supplied URL.
  md.renderer.rules.image=(tokens,index)=>escape(tokens[index].content||'');
  md.renderer.rules.link_open=()=>'<span class="link-label">';md.renderer.rules.link_close=()=>'</span>';
  root.ClassroomRenderer={html:text=>md.render(prepare(String(text).slice(0,50000))),math};
  root.renderAnswer=function(text){
    const element=root.document.getElementById('answer');element.innerHTML=root.ClassroomRenderer.html(text);
    return Math.max(root.document.body.scrollHeight,element.scrollHeight);
  };
})(typeof window==='object'?window:globalThis);
