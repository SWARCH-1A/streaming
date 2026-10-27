import amqp from 'amqplib';

import type { Log } from './log.ts';

/** Thrown by a handler: permanent goes straight to the DLQ, otherwise 1 s / 5 s / 30 s retries first. */
export class HandlerError extends Error {
  readonly reason: string;
  readonly permanent: boolean;
  constructor(reason: string, permanent: boolean) {
    super(reason);
    this.reason = reason;
    this.permanent = permanent;
  }
}

export interface IncomingMessage {
  body: string;
  contentType: string | undefined;
  routingKey: string;
}

export type Handler = (message: IncomingMessage) => Promise<string>;

export interface Publisher {
  publish(routingKey: string, body: string, messageId: string, type: string): Promise<void>;
}

const DELAYS = [1, 5, 30];

/**
 * One AMQPS connection per broker identity. Reconnects forever; every message is ACKed only after its
 * effect was committed or after a confirmed copy reached the retry/DLQ exchange (same rules as Chat).
 */
export class BrokerConnection implements Publisher {
  private readonly url: string;
  private readonly ca: string | null;
  private readonly log: Log;
  private readonly subscriptions = new Map<string, Handler>();
  private confirm: amqp.ConfirmChannel | null = null;
  private stopped = false;
  private connection: amqp.ChannelModel | null = null;

  constructor(url: string, ca: string | null, log: Log) {
    this.url = url;
    this.ca = ca;
    this.log = log;
  }

  subscribe(queue: string, handler: Handler): void {
    this.subscriptions.set(queue, handler);
  }

  async run(): Promise<void> {
    while (!this.stopped) {
      try {
        await this.session();
      } catch {
        // AMQP error strings may contain connection details; keep only the fact.
        if (!this.stopped) this.log.warn('broker no disponible', {});
      }
      this.confirm = null;
      if (!this.stopped) await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    await this.connection?.close().catch(() => undefined);
  }

  async publish(routingKey: string, body: string, messageId: string, type: string): Promise<void> {
    const channel = this.confirm;
    if (!channel) throw new Error('broker unavailable');
    await this.confirmedPublish(channel, 'p2.events', routingKey, Buffer.from(body), {
      contentType: 'application/json',
      persistent: true,
      messageId,
      type,
    });
  }

  private async session(): Promise<void> {
    const host = new URL(this.url).hostname;
    const connection = await amqp.connect(this.url, { ...(this.ca ? { ca: [this.ca] } : {}), servername: host, timeout: 3000 });
    this.connection = connection;
    const closed = new Promise<void>((resolve) => {
      connection.on('close', () => resolve());
      connection.on('error', () => undefined);
    });
    try {
      const confirm = await connection.createConfirmChannel();
      confirm.on('error', () => undefined);
      this.confirm = confirm;
      for (const [queue, handler] of this.subscriptions) {
        const channel = await connection.createChannel();
        channel.on('error', () => undefined);
        await channel.prefetch(8);
        await channel.consume(queue, (delivery) => {
          if (delivery) void this.deliver(queue, handler, channel, confirm, delivery);
        });
      }
      await closed;
    } finally {
      this.connection = null;
      await connection.close().catch(() => undefined);
    }
  }

  private async deliver(queue: string, handler: Handler, channel: amqp.Channel, confirm: amqp.ConfirmChannel,
      delivery: amqp.ConsumeMessage): Promise<void> {
    let reason = '';
    let permanent = false;
    try {
      const outcome = await handler({
        body: delivery.content.toString('utf8'),
        contentType: delivery.properties.contentType as string | undefined,
        routingKey: delivery.fields.routingKey,
      });
      this.log.info('broker consumido', { consumer: queue, outcome });
    } catch (error) {
      reason = error instanceof HandlerError ? error.reason : 'HANDLER_ERROR';
      permanent = error instanceof HandlerError && error.permanent;
    }
    try {
      if (reason) await this.retain(queue, confirm, delivery, reason, permanent);
      channel.ack(delivery);
    } catch {
      // Without a confirmed retry copy the message is left unacked; the broker redelivers it.
      this.log.warn('broker reintento no confirmado', { consumer: queue, cause: reason });
    }
  }

  private async retain(queue: string, confirm: amqp.ConfirmChannel, delivery: amqp.ConsumeMessage, reason: string,
      permanent: boolean): Promise<void> {
    const headers: Record<string, unknown> = { ...(delivery.properties.headers ?? {}) };
    const attempt = Math.max(0, Number(headers['x-p2-attempt'] ?? 0) || 0);
    const dead = permanent || attempt >= DELAYS.length;
    const now = Date.now();
    headers['x-p2-consumer'] = queue;
    headers['x-p2-cause'] = reason;
    headers['x-p2-attempt'] = attempt + 1;
    headers['x-p2-first-failure'] ??= now;
    headers['x-p2-last-failure'] = now;
    headers['x-p2-original-route'] ??= delivery.fields.routingKey;
    await this.confirmedPublish(confirm, dead ? `${queue}.dead` : `${queue}.retry`,
      dead ? 'failed' : String(DELAYS[attempt]), delivery.content, {
        headers,
        contentType: delivery.properties.contentType as string | undefined,
        persistent: true,
        messageId: delivery.properties.messageId as string | undefined,
        type: delivery.properties.type as string | undefined,
      });
    this.log.warn('broker entrega fallida', { consumer: queue, cause: reason, attempt: attempt + 1, deadLetter: dead });
  }

  private confirmedPublish(channel: amqp.ConfirmChannel, exchange: string, key: string, content: Buffer,
      options: amqp.Options.Publish): Promise<void> {
    return new Promise((resolve, reject) => {
      let returned = false;
      const onReturn = (message: amqp.Message) => {
        if (message.properties.messageId === options.messageId) returned = true;
      };
      channel.on('return', onReturn);
      const timer = setTimeout(() => finish(new Error('confirmation timeout')), 3000);
      function finish(error: Error | null) {
        clearTimeout(timer);
        channel.off('return', onReturn);
        if (error) reject(error);
        else if (returned) reject(new Error('unroutable'));
        else resolve();
      }
      try {
        channel.publish(exchange, key, content, { ...options, mandatory: true }, (error) =>
          finish(error ? new Error('nack') : null));
      } catch {
        finish(new Error('publish failed'));
      }
    });
  }
}
