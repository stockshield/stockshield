package com.stockshield.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @Async를 켠다. 이 설정이 없으면 @Async가 조용히 호출한 스레드에서 동기로 돈다.
 * 모든 @Async에는 실행기 이름을 적는다(예: @Async("fdsExecutor")). 실행기 빈은 쓰는 쪽 패키지에 둔다.
 * (FDS_TRD_001·FDS_AI_001 구현방안, 연결 합의서 3절)
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
