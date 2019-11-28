---
title: Wyliczanie urządzeń PCI-Express
layout: post
tags: [pci, hardware, x86]
---

W tym artykule postaram się opisać, w jaki sposób system operacyjny może wykryć i skonfigurować urządzenia podpięte do magristrali PCIe. Wyjaśnię mechanizm dostępu do przestrzeni konfiguracyjnej urządzeń PCIe, korzystając z linuksowego urządzenia `dev/mem`, umożliwiającego dostęp do fizycznej przestrzeni adresowej systemu.

## Topologia magistrali PCIe

Urządzenia PCIe można podzielić na dwie kategorie: urządzenia końcowe (_ang. endpoint_) i mostki (_ang. bridge_), które umożliwiają rozszerzenie magistrali poprzez dołączenie szyny kolejnego poziomu. W konsekwencji urządzenia PCIe obecne w systemie przyjmują strukturę drzewa.

![Drzewo urządzeń PCIe](/assets/img/pcitree.png)

Za wykrycie wszystkich mostków i ponumerowanie zarządzanych przez nie szyn odpowiada firmware komputera (np. BIOS) w pierwszych momentach jego działania. Analogicznie, urządzeniom w obrębie jednej szyny zostają sprzętowo nadane unikalne numery. Ponadto, jedno urządzenie fizyczne może udostępniać wiele urządzeń logicznych, tzw. funkcji (_ang. function), które mogą być niezależnie konfigurowane w systemie. 

Podsumowując, aby jednoznacznie zidentyfikować urządzenie logiczne, np. w celu odczytania z niego danych, należy posłużyć się adresem złożonym z następujących elementów:
* 8-bitowy numer szyny (_ang. bus number_)
* 5-bitowy numer urządzenia (_ang. device number_)
* 3-bitowy numer funkcji (_ang. function numer_)

## Przestrzeń konfiguracyjna urządzeń

## Tabela MCFG i skrypt listujący istniejące urządzenia

Adres bazowy przestrzeni konfiguracyjnej urządzeń PCI można odnaleźć w tabeli ACPI "MCFG". Format tabeli opisany jest w [specyfikacji][1].

```console
[root@host]# dd if=/sys/firmware/acpi/tables/MCFG bs=1 skip=44 count=8\
  2> /dev/null| hexdump -e '1/8 "%x\n"'
e0000000
```

[1]: http://read.pudn.com/downloads211/doc/comm/994029/pcifw_r3_0_updated.pdf "PCI Firmware Specification"