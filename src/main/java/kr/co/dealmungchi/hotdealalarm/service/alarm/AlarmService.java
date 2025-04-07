package kr.co.dealmungchi.hotdealalarm.service.alarm;

import kr.co.dealmungchi.hotdealalarm.domain.model.HotDealDto;
import reactor.core.publisher.Mono;

public interface AlarmService {
    Mono<Void> sendAlarm(HotDealDto hotDeal);
}
