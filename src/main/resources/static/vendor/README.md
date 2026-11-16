# 로컬 Markdown 의존성

- marked **18.0.14**, npm `marked-18.0.14.tgz`에서 UMD 파일을 그대로 포함합니다.
- 원본: https://github.com/markedjs/marked/releases/tag/v18.0.14
- npm 무결성(SHA-512): `mBHK6FBHuBAlhgRe88w9F0O1AbwwXJUcQibUbC/QcdTbVGAD7aWza+xt3N6oT/jCZx3/OMeS+8rnuiHZcQ9s7A==`
- 라이선스: [marked.LICENSE](marked.LICENSE)

CDN이나 실행 중 패키지 다운로드는 사용하지 않습니다. `markdown.js`는 HTML 생성기를 사용하지 않고 lexer 토큰을 허용된 DOM 요소와 text node로 변환합니다.
raw HTML은 텍스트로 표시하고, 실행형 URL을 차단합니다. Markdown 이미지는 외부 추적 요청을 피하려고 링크로 표시합니다.
