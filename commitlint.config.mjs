// conventional commits, checked on every pull request (commits.yml) and by the commit-msg hook
export default {
  extends: ['@commitlint/config-conventional'],
  // dependabot bodies paste release notes with long lines; its header is already conventional
  ignores: [(message) => message.includes('Signed-off-by: dependabot[bot]')],
  rules: {
    'header-max-length': [2, 'always', 120],
    'subject-case': [0]
  }
}
