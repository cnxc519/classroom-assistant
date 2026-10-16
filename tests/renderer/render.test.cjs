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
const discrete=String.raw`# 离散数学

**逻辑等价**：\(\neg(p\land q)\equiv\neg p\lor\neg q\)。

\[\forall x\in A,\ \exists y\in B:\ (x,y)\in R\]

\[A\cup B=\{x | x\in A\lor x\in B\},\quad A\cap B\subseteq A\]

| $p$ | $q$ | $p\to q$ |
| --- | --- | --- |
| 真 | 真 | 真 |
| 真 | 假 | 假 |

> 集合与命题要核对条件。

1. 自反性
2. 传递性

~~删除线~~ 与 *斜体*`;
let html=render(discrete);
assert.doesNotMatch(html,/katex-error|[\ue000-\ue0ff]/);
for(const tag of ['h1','strong','table','blockquote','ol','s','em'])assert.match(html,new RegExp('<'+tag+'[ >]'),tag);
assert.equal((html.match(/<th[ >]/g)||[]).length,3,'truth table columns');
const table=render(String.raw`| 集合 | 条件 |
| --- | --- |
| $\{x | x\in A\}$ | $P(A|B)$ |
| $\|x\|$ | $x\in A$ |`);
assert.equal((table.match(/<td[ >]/g)||[]).length,4,'math pipes must not split cells');
assert.doesNotMatch(table,/katex-error|[\ue000-\ue0ff]/);
for(const language of ['math','latex','tex']){
  html=render('```'+language+'\n'+String.raw`\forall x\in A,\ P(x)\implies Q(x)`+'\n```');
  assert.match(html,/class="katex"/);assert.doesNotMatch(html,/katex-error|<pre/);
}
html=render('```markdown\n'+discrete+'\n```');assert.match(html,/<table>/);assert.doesNotMatch(html,/<pre|katex-error/);
const literal=String.raw`$a|b$`;
html=render('```python\n'+literal+'\n```');assert.match(html,/<pre>/);assert.ok(html.includes(literal));
html=render('`'+literal+'`');assert.match(html,/<code>\$a\|b\$<\/code>/);
assert.doesNotMatch(render('```latex\n'+String.raw`\href{javascript:alert(1)}{x}`+'\n```'),/href="javascript:/);
console.log('PASS: common Markdown, wrappers, math fences, logic, sets, quantifiers, truth tables, formula pipes, streaming and untrusted HTML/URL');
