"""取消与不可中断的同步存储写入之间的边界。"""
import asyncio


async def finish_mutation(function, *args):
    """持锁等待线程写入结束，防止删除与取消后迟到的 upsert 交错。"""
    task = asyncio.create_task(asyncio.to_thread(function, *args))
    cancelled = False
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            cancelled = True
        except Exception:
            if not cancelled:
                raise
            break
    # 读取线程异常，取消始终优先向调用方传播，不能把已取消请求误报为成功。
    if cancelled:
        if not task.cancelled():
            task.exception()
        raise asyncio.CancelledError
    return task.result()
