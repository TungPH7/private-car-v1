# Phiên chính tự code một item, ngoài `/ao-build`

Chỉ dẫn cho phiên chính (Claude Code) khi con người bảo tự code một Task hoặc Bug thay cho dev agent. Chỉ bước code
là tự làm; mọi bước còn lại đi qua kit, để commit được deploy và item đi tới Done. Làm đủ 7 bước theo thứ tự, không bỏ
bước nào.

Chỉ dùng tài liệu này khi con người đã nói rõ với item này: tự code, không qua `/ao-build`. Không có câu đó thì dùng
luồng của kit (`ao-step run|build|review|qa <KEY> --go`).

## Ai làm gì

| Việc | Ai làm |
|---|---|
| Refine, Accept, `/ao-rollback`, merge PR đụng đường dẫn được bảo vệ | Chỉ con người |
| Approve PR ở cổng ③ | Con người. Phiên chính chỉ approve bằng login GitHub của con người khi con người đã cho phép rõ trong cuộc trò chuyện hiện tại |
| Start | Chỉ subagent mang tên role `fe-dev`, `be-dev` hoặc `devops`. Hook `jira-guard` từ chối mọi lần chuyển trạng thái của phiên chính |
| Comment, sửa trường của item | Phiên chính, qua MCP `atlassian` |
| Submit, Deploy to QA | jira-sync tự chuyển, không ai bấm |
| Pick up, QA fail | qa agent; hoặc phiên chính với item mức light (`light-qa`) |
| Merge | `merge-gate`; không ai merge tay, trừ con người với PR đụng đường dẫn được bảo vệ |

Sau mỗi bước, chạy `node .ai-orchestrator/scripts/ao-step.mjs status <KEY>` để xem item đã ở đúng trạng thái chưa, và
ghi bước vừa xong vào `.ai/ledger/<KEY>.md`.

## Bước 1. Có item, và con người Refine

1. Item chưa có thì tạo: con người gõ `/ao-intake`, hoặc phiên chính tạo bằng `createJiraIssue` theo
   `.ai-orchestrator/templates/task.md` hay `bug.md`, khi con người đã OK bản nháp.
   - Nhãn `ao-level-light`, `ao-level-standard` hoặc `ao-level-heavy`, theo mức con người duyệt.
   - Bug: thêm Found in (`customfield_10044`) và Severity (`customfield_10045`).
   - Task phải có mục `## Verification`; Bug phải có `## Steps to Reproduce` và `## Expected Result`. QA đọc các mục
     này. Mỗi dòng phải kiểm được trên Development từ bên ngoài, không cần vào VPS (bài học PCAR-4).
2. Dừng: con người bấm **Refine** (New → Ready). Phiên chính không bấm được.

## Bước 2. Tạo nhánh và push

1. Chọn tên nhánh: `feature/<KEY>-<slug>` cho Task; `bugfix/<KEY>-<slug>` cho Bug tìm thấy trên `develop`. Bảng nhánh
   gốc của Bug nằm trong `.claude/rules/ai-orchestrator.md`.
2. Tạo nhánh và push:

   ```bash
   git fetch origin
   git switch -c feature/<KEY>-<slug> origin/develop
   git push -u origin feature/<KEY>-<slug>
   ```

   Start cần nhánh có trên GitHub (guard `branch-created`).

## Bước 3. Start item

Phiên chính không tự Start được. Hook `jira-guard` từ chối với lý do "only an agent may move a Jira item; this call
does not come from a subagent named after a role of the contract" (thử trên PCAR-7). Start do một subagent mang tên role
làm.

1. Đọc item bằng `getJiraIssue`: phải ở **Ready**. Ở New thì dừng, chờ con người Refine.
2. Chạy một subagent `be-dev`, hoặc `fe-dev` khi phần việc chính là giao diện, bằng tool Agent. Giao nhiệm vụ hẹp:
   - chỉ chuyển **Start** (Ready → In Progress);
   - điền trường Agent (`customfield_10046`) đúng role của nó;
   - viết một comment, bắt đầu bằng giá trị Agent, ghi rằng phiên chính tự code item này;
   - nói rõ: không code, không commit, không mở PR, không ghi ledger.
3. Start bị từ chối thì đọc lý do và sửa đúng điều đó, như nhánh chưa push hay hết WIP. Không dùng transition khác để
   lách.

Quên bước này thì Submit và Deploy to QA không bao giờ chạy, và `/ao-qa` từ chối item vì nó còn ở Ready
(PCAR-1, 3, 4, 5, 6).

## Bước 4. Code, rồi mở PR

1. Code theo TDD, theo conventions của app: skill `web-conventions`, `admin-conventions`, `api-conventions`.
2. Chạy đủ cổng chất lượng cho mỗi app đã đụng:

   ```bash
   node .ai-orchestrator/scripts/run.mjs <app> lint
   node .ai-orchestrator/scripts/run.mjs <app> typecheck
   node .ai-orchestrator/scripts/run.mjs <app> test:coverage
   node .ai-orchestrator/scripts/run.mjs <app> build
   ```

3. Commit theo dạng `type(scope): subject (<KEY>)`, bằng tiếng Anh. Không dùng `--no-verify`. Push nhánh.
4. Không sửa đường dẫn được bảo vệ (`.ai-orchestrator/**`, `.claude/**`, `.github/workflows/**`…). Cần sửa thì báo con
   người trước: PR đó phải do con người merge.
5. Viết mô tả PR vào `.ai/runs/<KEY>/pr.md`, theo `.ai-orchestrator/templates/pull-request.md`:
   - đủ 6 mục: Jira, What Changed, How It Was Tested, Acceptance Criteria Coverage, Risks, Definition of Done;
   - không còn chỗ trống; xoá comment hướng dẫn và phần Example.
6. Mở PR bằng App `ao-dev`, không dùng `gh pr`:

   ```bash
   node .ai-orchestrator/scripts/ao-pr.mjs create --base develop --title "<type(scope): subject (KEY)>" --body-file .ai/runs/<KEY>/pr.md
   ```

7. Chờ CI xanh, đủ `build-test`, `policy`, `secrets`, `sast`: `gh run list --branch <nhánh>`, rồi `gh run watch <id>`.
   CI đỏ thì sửa, commit, push lại.
8. CI xanh thì jira-sync tự chuyển **Submit** (→ In Review). Kiểm bằng `ao-step status <KEY>`.

## Bước 5. Trace review, approve, merge, deploy

1. Chạy kiểm trước, rồi chạy thật:

   ```bash
   node .ai-orchestrator/scripts/ao-step.mjs review <PR>
   node .ai-orchestrator/scripts/ao-step.mjs review <PR> --go
   ```

   - Dự án đặt `review.default: off`, nên lệnh này chỉ đăng trace review-skip lên head, không chạy agent.
   - Item mức light mà PR chỉ có file docs (`docs/**`, `**/*.md`, ảnh, file dịch): lệnh đăng trace waiver
     `light-docs` thay cho trace review-skip, cũng không chạy agent (`.ai-orchestrator/quality/gates.yaml`).
   - Item có nhãn `ao-review-on`: reviewer và security chạy, có chi phí. Báo con người trước.
   - Lệnh liệt kê file critical thì đưa danh sách cho con người đọc kỹ diff trước khi approve.
2. Cổng ③: con người approve PR trên head mới nhất. Phiên chính chỉ approve thay khi con người đã cho phép rõ.
3. `merge-gate` tự merge. Không merge tay. PR đụng đường dẫn được bảo vệ: kit báo con người tự merge.
4. Deployer kiểm từng commit mới của `develop` và chờ CI trên head `develop` xanh, rồi mới deploy Development.
   - Deploy xong, jira-sync tự chuyển **Deploy to QA** (→ Ready for QA).
   - Audit bị từ chối: đọc lý do, báo con người. Chỉ con người gỡ, bằng `/ao-rollback development <sha>`. Commit hỏng
     chặn deploy của mọi commit sau nó.

## Bước 6. QA

Trước khi chạy, kiểm item ở **Ready for QA**.

**Item mức light** (phiên chính tự kiểm, không tốn agent):
1. Chạy `node .ai-orchestrator/scripts/ao-step.mjs qa <KEY> --go`. Lệnh Pick up item (→ In QA) và in ra các dòng phải
   kiểm.
2. Kiểm từng dòng trên Development như trình duyệt thật: request ghi (POST, PUT, DELETE) gửi `Origin` của host app, và
   thử thêm một `Origin` lạ, phải bị 403 (bài học PCAR-6).
3. Viết `.ai/runs/<KEY>/light-report.md` theo `.ai-orchestrator/templates/qa-report.md`:
   - tiêu đề `# QA report: <KEY> <tên item>`;
   - dưới `## Result` ghi `PASS`, `FAIL` hoặc `NOT TESTED`.
4. Chạy `node .ai-orchestrator/scripts/ao-step.mjs qa <KEY> --light-report .ai/runs/<KEY>/light-report.md --go`.
   - Lệnh đăng báo cáo lên item. FAIL thì chuyển QA fail.
   - PR đụng đường dẫn được bảo vệ thì mức light không áp dụng: qa agent chạy.

**Item mức standard hoặc heavy:**
1. Con người gõ `/ao-qa <KEY>`, hoặc phiên chính chạy `node .ai-orchestrator/scripts/ao-step.mjs qa <KEY> --go` khi
   con người đã cho phép. qa agent chạy, có chi phí.
2. QA cần `E2E_ADMIN_EMAIL` và `E2E_ADMIN_PASSWORD` trong môi trường. Phiên mở trước khi đặt hai biến này thì chạy qua
   wrapper đọc biến người dùng của Windows, không in giá trị ra.
3. Sau khi chạy, đọc log qa trong `.ai/runs/<KEY>/` xem có lộ mật khẩu không. Có thì thay bằng `[REDACTED]`.

**QA fail:** item quay về In Progress. Làm lại từ bước 2 trên nhánh mới `<nhánh cũ>-r2`, `-r3`…

## Bước 7. Con người Accept

1. Báo con người: link báo cáo QA PASS, và nhắc bấm **Accept** (In QA → Done). Phiên chính không bấm được.
2. Sau Accept, kiểm bằng `ao-step status <KEY>`: item ở Done.

## Trước bản release

PR đã bỏ qua review (trace review-skip) phải được review sau merge trước khi lên Production: con người gõ
`/ao-review <KEY>`. Thiếu bước này thì PR release vào `main` không merge được.

## Những lỗi đã gặp

| Lỗi | Hậu quả | Cách tránh |
|---|---|---|
| Không Start item (PCAR-1, 3, 4, 5, 6) | Item kẹt ở Ready, không QA được | Bước 3, bằng subagent role, trước commit đầu tiên |
| Phiên chính tự gọi `transitionJiraIssue` (PCAR-7) | `jira-guard` từ chối | Mọi lần chuyển trạng thái đều do subagent role, jira-sync, `ao-step` hoặc con người |
| Dòng Verification cần vào VPS (PCAR-4) | qa báo NOT TESTED | Bước 1: dòng nào cũng kiểm được từ bên ngoài |
| Kiểm bằng script không gửi `Origin` (PCAR-6) | Lọt lỗi CORS 403, tốn thêm vòng 3 | Bước 6: kiểm như trình duyệt |
| Mật khẩu admin lọt vào log qa | Phải che lại bằng tay | Bước 6: đọc log sau mỗi lần qa chạy |
| Đổi phạm vi giữa chừng (sửa test đỏ thành làm cả JWT) | Làm ngoài ý con người | Phạm vi lớn lên thì hỏi lại trước khi làm |
