"""접미사 구조, 다중 패턴, 주기와 회전."""
import random


def patterns(text, needles):
    from collections import deque
    edges=[{}]
    terminal=[]
    for word in needles:
        v=0
        for c in word:
            if c not in edges[v]:
                edges[v][c]=len(edges)
                edges.append({})
            v=edges[v][c]
        terminal.append(v)
    fail=[0]*len(edges)
    order=[]
    # 각 상태에 26개 문자 전이를 채워 접미 링크를 반복 탐색하지 않습니다.
    hops=[None]*len(edges)
    hops[0]=[edges[0].get(chr(ord('a')+i),0) for i in range(26)]
    queue=deque(edges[0].values())
    while queue:
        v=queue.popleft()
        order.append(v)
        fallback=hops[fail[v]]
        row=fallback.copy()
        for c,w in edges[v].items():
            i=ord(c)-ord('a')
            fail[w]=fallback[i]
            row[i]=w
            queue.append(w)
        hops[v]=row
    count=[0]*len(edges)
    v=0
    for c in text:
        v=hops[v][ord(c)-ord('a')]
        count[v]+=1
    for v in reversed(order):
        count[fail[v]]+=count[v]
    return [count[v] for v in terminal]


def suffixes(text):
    n=len(text)
    order=list(range(n))
    ranks=[ord(c) for c in text]
    width=1
    while width<n:
        def key(i):
            return (ranks[i], ranks[i+width] if i+width<n else -1)
        order.sort(key=key)
        nxt=[0]*n
        for j in range(1,n):
            a,b=order[j-1],order[j]
            nxt[b]=nxt[a]+(key(a)!=key(b))
        ranks=nxt
        if ranks[order[-1]]==n-1:
            break
        width*=2
    return order


def lcp(text):
    n=len(text)
    order=suffixes(text)
    rank=[0]*n
    for i,v in enumerate(order):
        rank[v]=i
    result=[0]*n
    size=0
    for i in range(n):
        k=rank[i]
        if k==0:
            size=0
            continue
        j=order[k-1]
        while i+size<n and j+size<n and text[i+size]==text[j+size]:
            size+=1
        result[k]=size
        size=max(0,size-1)
    return result


def distinct(text):
    return len(text)*(len(text)+1)//2-sum(lcp(text))


def longest_common(first, second):
    trans=[{}]
    link=[-1]
    length=[0]
    last=0
    for c in first:
        cur=len(trans)
        trans.append({})
        length.append(length[last]+1)
        link.append(0)
        p=last
        while p>=0 and c not in trans[p]:
            trans[p][c]=cur
            p=link[p]
        if p>=0:
            q=trans[p][c]
            if length[p]+1==length[q]:
                link[cur]=q
            else:
                clone=len(trans)
                trans.append(trans[q].copy())
                length.append(length[p]+1)
                link.append(link[q])
                while p>=0 and trans[p].get(c)==q:
                    trans[p][c]=clone
                    p=link[p]
                link[cur]=link[q]=clone
        last=cur
    best=size=v=0
    for c in second:
        while v and c not in trans[v]:
            v=link[v]
            size=length[v]
        if c in trans[v]:
            v=trans[v][c]
            size+=1
        else:
            size=0
        best=max(best,size)
    return best


def periods(text):
    n=len(text)
    prefix=[0]*n
    answer=[]
    for i in range(1,n):
        j=prefix[i-1]
        while j and text[i]!=text[j]:
            j=prefix[j-1]
        if text[i]==text[j]:
            j+=1
        prefix[i]=j
        size=i+1
        period=size-j
        if period<size and size%period==0:
            answer.append([size,size//period])
    return answer


def rotation(text):
    n=len(text)
    if n==0:
        return ""
    twice=text+text
    a,b,k=0,1,0
    while a<n and b<n and k<n:
        if twice[a+k]==twice[b+k]:
            k+=1
            continue
        if twice[a+k]>twice[b+k]:
            a+=k+1
            if a==b:
                a+=1
        else:
            b+=k+1
            if b==a:
                b+=1
        k=0
    start=min(a,b)
    return twice[start:start+n]


def brute_lcp(s):
    order=sorted(s[i:] for i in range(len(s)))
    answer=[0] if s else []
    for a,b in zip(order,order[1:]):
        k=0
        while k<min(len(a),len(b)) and a[k]==b[k]: k+=1
        answer.append(k)
    return answer


def brute_period(s):
    out=[]
    for n in range(2,len(s)+1):
        for k in range(1,n):
            if n%k==0 and s[:k]*(n//k)==s[:n]:
                out.append([n,n//k])
                break
    return out


def populate(add):
    r=random.Random(1410)
    def word(n): return ''.join(r.choices('abcz',k=n))
    texts=[[t] for t in ['banana','','a','aaaa','abababa','abcab','caba','z','abcabcabc']]
    texts.extend([[word(n)] for n in range(3,19)])
    many=[['aaaa',['a','aa','aaa','aa','b']],['',[]],['ushers',['she','he','hers']]]
    many.extend([[word(k+3),[word(r.randint(1,5)) for _ in range(k+1)]] for k in range(15)])
    lim="문자열은 영문 소문자로만 이루어지며 길이는 각각 0~16,000입니다."
    add("아호-코라식 문자열 검색","text:string needles:string[]","int[]","본문 text에서 여러 패턴을 동시에 찾으세요. 시작 위치가 다르면 겹치는 등장도 따로 셉니다. 같은 패턴이 여러 번 주어지면 각각 답하세요.\n각 패턴의 등장 횟수를 needles의 입력 순서로 반환하세요.",lim+" needles는 최대 5,000개의 비어 있지 않은 패턴이며 길이의 합은 50,000 이하입니다.",patterns,many,lambda s,ws:[sum(s.startswith(w,i) for i in range(len(s))) for w in ws],stress=[(['a'*8000,['a'*i for i in range(1,101)]],list(range(8000,7900,-1)))])
    uniform='a'*4000
    add("접미사 배열","text:string","int[]","text의 비어 있지 않은 모든 접미사를 사전순으로 정렬하세요. 한 문자열이 다른 문자열의 접두사이면 짧은 쪽이 먼저입니다.\n정렬 순서대로 각 접미사의 0부터 시작하는 위치를 배열로 반환하세요.",lim,suffixes,texts,lambda s:sorted(range(len(s)),key=lambda i:s[i:]),stress=[([uniform],list(range(3999,-1,-1)))])
    add("LCP 배열","text:string","int[]","비어 있지 않은 접미사를 사전순으로 정렬하고 이웃한 두 접미사의 최장 공통 접두사 길이를 계산하세요.\n첫 값을 0으로 하고 i번째 값에 i−1번째와 i번째 접미사의 공통 길이를 넣으세요. text가 비면 빈 배열입니다.",lim,lcp,texts,brute_lcp,dependencies=(suffixes,),stress=[([uniform],list(range(4000)))])
    add("서로 다른 부분 문자열","text:string","int","문자열에서 연속한 한 글자 이상을 잘라 만들 수 있는 값을 셉니다. 위치가 달라도 내용이 같으면 하나로 취급합니다.\n서로 다른 비어 있지 않은 부분 문자열의 수를 반환하세요.",lim,distinct,texts,lambda s:len({s[i:j] for i in range(len(s)) for j in range(i+1,len(s)+1)}),dependencies=(suffixes,lcp),stress=[([uniform],4000)])
    pairs=[['ababc','babca'],['abc','xyz'],['','abc'],['banana','ana']]
    pairs.extend([[word(n),word(n+3)] for n in range(1,16)])
    add("접미사 자동자","first:string second:string","int","first의 접미사 자동자를 구성하여 두 문자열에 공통으로 나타나는 연속 부분 문자열을 찾으세요.\n가장 긴 공통 부분 문자열의 길이를 반환하세요. 공통 글자가 없으면 0입니다.",lim,longest_common,pairs,lambda a,b:max([0]+[j-i for i in range(len(a)) for j in range(i+1,len(a)+1) if a[i:j] in b]),stress=[(['ab'*3000,'z'+'ba'*2000+'z'],4000)])
    add("문자열 주기 분석","text:string","int[][]","text의 각 비어 있지 않은 접두사에서 더 짧은 문자열을 정수 번 반복하여 정확히 같은 접두사를 만들 수 있는지 검사하세요.\n2회 이상 반복되는 접두사마다 [접두사 길이, 최대 반복 횟수]를 길이 오름차순으로 반환하세요.",lim,periods,[["abababa"],["banana"]]+texts,brute_period,stress=[(['az'*1500],[[2*k,k] for k in range(2,1501)])])
    add("최소 문자열 회전","text:string","string","text의 앞쪽 글자를 차례대로 맨 뒤로 옮겨 얻을 수 있는 순환 이동 문자열을 모두 고려하세요. 원래 문자열도 후보입니다.\n사전순으로 가장 작은 문자열을 반환하세요. 빈 문자열이면 빈 문자열입니다.",lim,rotation,texts,lambda s:min((s[i:]+s[:i] for i in range(len(s))),default=''),stress=[(['z'*5000+'a'],'a'+'z'*5000)])
