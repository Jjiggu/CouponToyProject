# CouponToyProject
쿠폰 발급 처리 토이프로젝트입니다.

## 💡 프로젝트 주제

- **대규모 트래픽 상황을 가정한 선착순 쿠폰 발급 서비스**

<br>

## 🏗 아키텍처

## 추후 업로드 예정

<br>

## 📝 프로젝트 개요

- [목적] 특정 시간/조건에 한해 선착순으로 발급되는 쿠폰을 안정적으로 처리하고, 동시성 이슈와 대규모 트래픽 환경에서의 백엔드 설계를 학습 및 검증한다. 
- [배경] 올리브영 테크 블로그 "올리브영 쿠폰 발급 개선 이야기" 칼럼을 읽은 후 대규모 트래픽 환경에서 쿠폰 발급 과정에 대한 궁금증 발생

<br>

## 📝 프로젝트 목표

- **동시성 제어**
    - 한정된 쿠폰을 여러 사용자가 거의 동시에 요청할 경우, **중복/초과 발급**이 일어나지 않도록 해야한다.
    - DB 수준(Optimistic/Pessimistic Lock) 혹은 Redis 분산락 등 적용 방법 결정한다.
- **성능**
    - 선착순 이벤트 시작 시점에 **급격한 트래픽**이 몰릴 것을 가정한다.
    - 1초에 1,000~10,000 TPS(또는 그 이상의 트래픽)도 안정적으로 처리할 수 있도록 고려한다.

<br>

## 🚀 프로젝트 인원 및 기간

- **개발 인원**: BE 1명
- **프로젝트 기간**: 25.03 ~ 진행중

<br>

### 기능 요구사항

##(1) 회원 관리

1. 회원가입
    - 사용자 정보(`email`, `password`, `name`, `nickname` 등) 등록
    - `email`은 유니크해야 함
    - 비밀번호는 암호화 처리
2. 로그인 
    - `email` + `password` 기반 인증
    - 로그인 성공 시 JWT 토큰 발급

##(2) 쿠폰 등록/관리

1. 쿠폰 생성
    - 쿠폰 이름 (`name`), 총 발급 가능 수량 (`totalCount`), 발급 조건 등 설정
    - 생성 시 쿠폰 고유 `id` 자동 부여

##(3) 쿠폰 발급 (선착순)

1. 발급 요청 
    - 사용자 인증 필요
    - 요청 시 남은 수량(totalCount - issuedCount)이 1 이상인지, 발급 조건 맞는지 체크
    - 이미 발급 받은 쿠폰이 있는지 (UserCoupon 테이블에서 중복 확인) 체크
2. 발급 결과
    - 성공 시 issuedCount 1 증가, UserCoupon 테이블에 기록
    - 실패 시 (쿠폰 소진, 중복 발급, 기간 외) 에러 메시지 반환

(4) 쿠폰 조회

1. 사용자 측 조회
    - 본인이 발급받은 쿠폰 목록 및 상세 정보 조회
2. 관리자 측 조회
    - 특정 쿠폰의 발급 현황(발급 수량, 사용자 리스트) 조회

<br>

## ⚙️ 프로젝트 세팅

> 1. 자바 버전 : 17
> 2. 스프링부트 버전 : 3.4.1
> 3. 빌드 & 빌드 도구 : Gradle
> 4. Git 브랜치 전략 : Feature Branch → Develop Branch → Main Branch(배포 예정)

<br>

## 🛠️ 기술 스택
#### Framework
![springboot](https://img.shields.io/badge/springboot-6DB33F.svg?style=for-the-badge&logo=springboot&logoColor=white)
![springsecurity](https://img.shields.io/badge/springsecurity-6DB33F.svg?style=for-the-badge&logo=springsecurity&logoColor=white)

#### DB
![mysql](https://img.shields.io/badge/mysql-4479A1.svg?style=for-the-badge&logo=mysql&logoColor=white)
![redis](https://img.shields.io/badge/redis-DC382D.svg?style=for-the-badge&logo=redis&logoColor=white)

#### Library
![JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Lombok](https://img.shields.io/badge/lombok-E50005.svg?style=for-the-badge&logo=lospec&logoColor=white)
![jwt](https://img.shields.io/badge/jwt-000000.svg?style=for-the-badge&logo=jsonwebtokens&logoColor=white)

<br>


## 📐 ERD 설계도

<img width="984" alt="image" src="https://github.com/user-attachments/assets/f61056de-aedc-4849-8d29-4ce59c9e609c">

