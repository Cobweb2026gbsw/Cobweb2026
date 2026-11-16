// JWT 구조와 실제 문자열 sub를 가진 fixture입니다. 서명은 테스트 전용이며 인증을 증명하지 않습니다.
export function jwtFixture(subject = '1', marker = 'test') {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({alg: 'HS256', typ: 'JWT'})}.${encode({sub: String(subject), username: `account-${subject}`, role: 'USER', tokenVersion: 0, exp: 4102444800, jti: marker})}.${Buffer.from('test-only-signature').toString('base64url')}`;
}
