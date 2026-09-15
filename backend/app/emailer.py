"""通过 SMTP 发送平台账户验证码邮件（QQ 邮箱等标准 SMTP 服务）。"""
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import formataddr, parseaddr

from .core import SMTP_FROM, SMTP_HOST, SMTP_PASS, SMTP_PORT, SMTP_USER
from .mail import MailError

PURPOSE_TEXT = {'register': '注册账户', 'reset': '重置密码'}


def smtp_configured():
    return bool(SMTP_HOST and SMTP_USER and SMTP_PASS and SMTP_FROM)


def _sender():
    name, address = parseaddr(SMTP_FROM)
    return (formataddr((name, address)) if name else address), address


def _body(code, purpose):
    minutes = 10
    subject = f'Hmail {PURPOSE_TEXT[purpose]}验证码'
    text = (
        f'你正在{PURPOSE_TEXT[purpose]}，验证码为：{code}\n\n'
        f'验证码 {minutes} 分钟内有效，请勿泄露给他人。\n'
        '如果这不是你本人的操作，请忽略此邮件。\n'
    )
    html = (
        '<div style="font:15px/1.7 system-ui,\'Segoe UI\',Roboto,sans-serif;color:#263238">'
        f'<p>你正在{PURPOSE_TEXT[purpose]}，验证码为：</p>'
        f'<p style="font-size:30px;font-weight:600;letter-spacing:6px;color:#1a73e8">{code}</p>'
        f'<p style="color:#5f6368">验证码 {minutes} 分钟内有效，请勿泄露给他人。</p>'
        '<p style="color:#5f6368">如果这不是你本人的操作，请忽略此邮件。</p>'
        '</div>'
    )
    return subject, text, html


def send_code(to_email, code, purpose):
    if not smtp_configured():
        raise MailError('邮件服务尚未配置，暂时无法发送验证码', 'smtp_not_configured', 503)
    sender, address = _sender()
    subject, text, html = _body(code, purpose)
    message = EmailMessage()
    message['From'] = sender
    message['To'] = to_email
    message['Subject'] = subject
    message.set_content(text)
    message.add_alternative(html, subtype='html')
    try:
        if SMTP_PORT == 465:
            with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=15, context=ssl.create_default_context()) as client:
                client.login(SMTP_USER, SMTP_PASS)
                client.send_message(message, from_addr=address, to_addrs=[to_email])
        else:
            with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15) as client:
                client.ehlo()
                client.starttls(context=ssl.create_default_context())
                client.ehlo()
                client.login(SMTP_USER, SMTP_PASS)
                client.send_message(message, from_addr=address, to_addrs=[to_email])
    except smtplib.SMTPAuthenticationError:
        raise MailError('邮件服务认证失败，请检查 SMTP 授权码配置', 'smtp_auth', 502) from None
    except (smtplib.SMTPException, OSError):
        raise MailError('验证码邮件发送失败，请稍后重试', 'smtp_error', 502) from None
