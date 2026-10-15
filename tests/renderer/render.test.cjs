const fs=require('fs');const path=require('path');const vm=require('vm');const assert=require('assert/strict');
const root=path.resolve(__dirname,'../../assets/renderer');
const context={console,atob,btoa,TextDecoder,TextEncoder,setTimeout,clearTimeout};vm.createContext(context);
for(const filename of ['katex.min.js','markdown-it.min.js','answer.js']){
  try{vm.runInContext(fs.readFileSync(path.join(root,filename),'utf8'),context,{filename});}
  catch(error){console.error(filename+': '+error.message);process.exit(1);}
}
const render=text=>context.ClassroomRenderer.html(text);
const cases=[
  ['inline brackets',String.raw`二分之一是 \(\frac{1}{2}\)。`],
  ['inline dollars',String.raw`复杂度是 $O(\log n)$。`],
  ['display brackets',String.raw`\[x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}\]`],
  ['multiline dollars',String.raw`$$
\int_0^1 x^2\,dx=\frac13
$$`],
  ['matrix',String.raw`\[\begin{bmatrix}1&2\\3&4\end{bmatrix}\]`],
  ['unicode text',String.raw`概率 \(P(A\mid B)=\frac{P(B\mid A)P(A)}{P(B)}\)。`]
];
for(const [name,source] of cases){const html=render(source);assert.match(html,/class="katex"/,name);assert.doesNotMatch(html,/katex-error/,name);}
assert.match(render('**重点**\n\n- 第一点\n- 第二点\n\n```python\nprint(1)\n```'),/<strong>重点<\/strong>/);
const attack=render('<script>alert(1)</script>\n\n![tracking](https://evil.invalid/x)\n\n[click](javascript:alert(1))');
assert.doesNotMatch(attack,/<script|<img|href="javascript:/);
const maliciousMath=render(String.raw`\(\href{javascript:alert(1)}{点我}\)`);assert.doesNotMatch(maliciousMath,/href="javascript:/);
for(const source of [String.raw`\(\frac{1}{`,String.raw`$$\begin{matrix}`,String.raw`\(\notarealcommand{x}\)`])assert.doesNotThrow(()=>render(source));
assert.doesNotMatch(render('价格是 $5 和 $10。'),/class="katex"/);
console.log('PASS: brackets/dollars, fractions, roots, integrals, matrices, streaming fragments, Markdown, and untrusted HTML/URL handling');
