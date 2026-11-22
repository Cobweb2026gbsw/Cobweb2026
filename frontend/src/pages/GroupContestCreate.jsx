import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import GroupContestForm from '../components/GroupContestForm';
import { ProtocolError } from '../lib/errors';
import useApiData from '../lib/useApiData';
import {contestParent} from '../lib/groupNavigation';
import './common.css';
import './Groups.css';

export default function GroupContestCreate({ dark }) {
  const { gid } = useParams();
  const { epoch } = useAuth();
  const navigate = useNavigate();
  const {state} = useLocation();
  const validGroup = /^[1-9]\d{0,18}$/.test(gid ?? '');
  const groupPath = validGroup ? `/groups/${gid}` : '/groups';
  const { data, error, loading } = useApiData(validGroup ? `/api${groupPath}` : null);
  const canCreate = Array.isArray(data?.permissions) && data.permissions.includes('CREATE_CONTESTS');

  function openContest(id) {
    if (!Number.isSafeInteger(id) || id <= 0) {
      throw new ProtocolError(undefined, { requestMayHaveCompleted: true });
    }
    navigate(`${groupPath}/contests/${id}`, { replace: true, state });
  }

  return <main className={`cw-page gp-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap">
    <header className="cw-head">
      <div>
        {validGroup && <p className="cw-eyebrow">GROUP / #{gid}</p>}
        <h1 className="cw-title">새 코테 만들기</h1>
        {data?.group?.groupName && <p className="cw-desc">{data.group.groupName}</p>}
      </div>
      <Link to={validGroup ? contestParent(gid, state).to : groupPath} className="cw-btn"><span aria-hidden="true">← </span>{validGroup ? '그룹으로' : '그룹 목록'}</Link>
    </header>
    {!validGroup && <p className="gp-error" role="alert">그룹 주소를 확인해 주세요.</p>}
    {loading && <p role="status">그룹 정보를 불러오는 중입니다.</p>}
    {error && <p className="gp-error" role="alert">{error}</p>}
    {data && (canCreate ? <section className="cw-card" aria-labelledby="contest-settings-title">
      <h2 className="cw-card-title" id="contest-settings-title">코테 설정</h2>
      <GroupContestForm key={`${gid}:${epoch}`} gid={gid} onSaved={openContest} hideHeading />
    </section> : <p className="gp-error" role="alert">이 그룹에서 코테를 만들 권한이 없습니다.</p>)}
  </div></main>;
}
