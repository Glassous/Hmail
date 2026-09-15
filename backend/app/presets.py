"""Common mailbox provider presets; served to the client through GET /config."""

MAIL_PRESETS = [
    {
        'id': 'qq',
        'name': 'QQ 邮箱',
        'domains': ['qq.com', 'vip.qq.com', 'foxmail.com'],
        'imap': {'host': 'imap.qq.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.qq.com', 'port': 465, 'security': 'ssl'},
        'hint': '需在“设置 → 账户”开启 IMAP/SMTP 服务，并使用生成的授权码作为密码。',
    },
    {
        'id': '163',
        'name': '网易 163 邮箱',
        'domains': ['163.com', 'vip.163.com'],
        'imap': {'host': 'imap.163.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.163.com', 'port': 465, 'security': 'ssl'},
        'hint': '需开启 IMAP/SMTP 服务，并使用客户端授权码作为密码。',
    },
    {
        'id': '126',
        'name': '网易 126 邮箱',
        'domains': ['126.com', 'vip.126.com'],
        'imap': {'host': 'imap.126.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.126.com', 'port': 465, 'security': 'ssl'},
        'hint': '需开启 IMAP/SMTP 服务，并使用客户端授权码作为密码。',
    },
    {
        'id': 'gmail',
        'name': 'Gmail',
        'domains': ['gmail.com', 'googlemail.com'],
        'imap': {'host': 'imap.gmail.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.gmail.com', 'port': 465, 'security': 'ssl'},
        'hint': '需开启两步验证并创建应用专用密码；也可改用上方的 Google 授权连接。',
    },
    {
        'id': 'outlook',
        'name': 'Outlook / Hotmail',
        'domains': ['outlook.com', 'hotmail.com', 'live.com', 'msn.com'],
        'imap': {'host': 'outlook.office365.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.office365.com', 'port': 587, 'security': 'starttls'},
        'hint': '微软已停用基础认证，多数账户需使用应用密码或改用支持 OAuth 的客户端。',
    },
    {
        'id': 'icloud',
        'name': 'iCloud 邮箱',
        'domains': ['icloud.com', 'me.com', 'mac.com'],
        'imap': {'host': 'imap.mail.me.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.mail.me.com', 'port': 587, 'security': 'starttls'},
        'hint': '需在 Apple ID 中生成“App 专用密码”作为密码。',
    },
    {
        'id': 'sina',
        'name': '新浪邮箱',
        'domains': ['sina.com', 'sina.cn'],
        'imap': {'host': 'imap.sina.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.sina.com', 'port': 465, 'security': 'ssl'},
        'hint': '需在邮箱设置中开启 IMAP/SMTP 服务。',
    },
    {
        'id': 'sohu',
        'name': '搜狐邮箱',
        'domains': ['sohu.com'],
        'imap': {'host': 'imap.sohu.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.sohu.com', 'port': 465, 'security': 'ssl'},
        'hint': '需在邮箱设置中开启 IMAP/SMTP 服务。',
    },
    {
        'id': '139',
        'name': '中国移动 139 邮箱',
        'domains': ['139.com'],
        'imap': {'host': 'imap.139.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.139.com', 'port': 465, 'security': 'ssl'},
        'hint': '需在邮箱设置中开启 IMAP/SMTP 服务。',
    },
    {
        'id': 'aliyun',
        'name': '阿里云邮箱',
        'domains': ['aliyun.com', 'aliyun.cn'],
        'imap': {'host': 'imap.aliyun.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.aliyun.com', 'port': 465, 'security': 'ssl'},
        'hint': '需在邮箱设置中开启 IMAP/SMTP 服务。',
    },
    {
        'id': 'exmail',
        'name': '腾讯企业邮箱',
        'domains': ['exmail.qq.com'],
        'imap': {'host': 'imap.exmail.qq.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.exmail.qq.com', 'port': 465, 'security': 'ssl'},
        'hint': '使用企业邮箱地址与客户端专用密码（非登录密码）。',
    },
    {
        'id': 'yahoo',
        'name': 'Yahoo Mail',
        'domains': ['yahoo.com', 'yahoo.co.jp', 'ymail.com'],
        'imap': {'host': 'imap.mail.yahoo.com', 'port': 993, 'security': 'ssl'},
        'smtp': {'host': 'smtp.mail.yahoo.com', 'port': 465, 'security': 'ssl'},
        'hint': '需在账户安全设置中生成“应用密码”作为密码。',
    },
]

CUSTOM_PRESET = {
    'id': 'custom',
    'name': '自定义 / 其他邮箱',
    'domains': [],
    'imap': {'host': '', 'port': 993, 'security': 'ssl'},
    'smtp': {'host': '', 'port': 465, 'security': 'ssl'},
    'hint': '请向邮箱服务商确认 IMAP 与 SMTP 服务器地址、端口及加密方式。',
}


def preset_for_email(email):
    """Return the preset matching an address domain, or None when unknown."""
    if not email or '@' not in email:
        return None
    domain = email.rsplit('@', 1)[1].strip().lower()
    for preset in MAIL_PRESETS:
        if domain in preset['domains']:
            return preset
    return None
