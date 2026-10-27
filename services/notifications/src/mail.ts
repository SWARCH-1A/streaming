import nodemailer from 'nodemailer';

export type SendOutcome = 'DELIVERED' | 'RETRYABLE_FAILURE' | 'PERMANENT_FAILURE' | 'UNKNOWN';

export interface MailMessage {
  to: string;
  subject: string;
  text: string;
}

export interface Mailer {
  send(message: MailMessage): Promise<SendOutcome>;
}

/**
 * SMTP answers are classified, never logged with the address: 4xx and failures before the
 * transaction are retryable, 5xx is permanent, a failure after DATA started is UNKNOWN.
 */
export function classifySmtpError(error: unknown): SendOutcome {
  const value = error as { responseCode?: number; code?: string; command?: string };
  if (typeof value.responseCode === 'number') {
    if (value.responseCode >= 500) return 'PERMANENT_FAILURE';
    if (value.responseCode >= 400) return 'RETRYABLE_FAILURE';
  }
  if (value.command === 'DATA' || value.command === 'MESSAGE' || value.code === 'ETIMEDOUT') return 'UNKNOWN';
  return 'RETRYABLE_FAILURE';
}

export function smtpMailer(options: { host: string; port: number; requireTls: boolean; from: string; ca: string | null }): Mailer {
  const transport = nodemailer.createTransport({
    host: options.host,
    port: options.port,
    secure: false,
    requireTLS: options.requireTls,
    connectionTimeout: 5000,
    greetingTimeout: 5000,
    socketTimeout: 10000,
    tls: { rejectUnauthorized: true, ...(options.ca ? { ca: options.ca } : {}) },
    logger: false,
  });
  return {
    async send(message) {
      try {
        await transport.sendMail({ from: options.from, to: message.to, subject: message.subject, text: message.text });
        return 'DELIVERED';
      } catch (error) {
        return classifySmtpError(error);
      }
    },
  };
}

/** Used when SMTP is not configured: the delivery stays observable instead of silently succeeding. */
export const unconfiguredMailer: Mailer = {
  async send() {
    return 'RETRYABLE_FAILURE';
  },
};
