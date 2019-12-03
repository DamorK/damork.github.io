---
title: Wyliczanie urządzeń PCI-Express
layout: post
tags: [pci, hardware, x86, linux]
---

Cześć :). Dziś postaram się opisać, w jaki sposób system operacyjny może wykryć i skonfigurować urządzenia podpięte do magistrali PCIe. Wyjaśnię mechanizm dostępu do przestrzeni konfiguracyjnej urządzeń PCIe, korzystając z linuksowego urządzenia `/dev/mem`, reprezentującego fizyczną przestrzeń adresową systemu.

## Topologia magistrali PCIe

Urządzenia PCIe można podzielić na dwie kategorie: urządzenia końcowe (_ang. endpoint_) i mostki (_ang. bridge_), które umożliwiają rozszerzenie magistrali poprzez dołączenie szyny kolejnego poziomu. W konsekwencji urządzenia PCIe obecne w systemie przyjmują formę drzewa, na przykład:

![Drzewo urządzeń PCIe](/assets/img/pcitree.png)

Za wykrycie wszystkich mostków i ponumerowanie zarządzanych przez nie szyn odpowiada firmware (BIOS) zaraz po uruchomieniu komputera. Urządzenia w obrębie jednej szyny również mają nadane unikalne numery (sprzętowo). Ponadto, jedno urządzenie fizyczne może udostępniać wiele urządzeń logicznych, tzw. funkcji (_ang. function_), które mogą być traktowane przez system jako niezależne byty.

Zatem, aby odnaleźć w tym gąszczu wybrane urządzenie, potrzebny jest adres złożony z trzech komponentów:
* 8-bitowy numer szyny (_ang. bus number_): <0-255>
* 5-bitowy numer urządzenia (_ang. device number_): <0-31>
* 3-bitowy numer funkcji (_ang. function numer_): <0-7>

Często można spotkać się z zapisem tego adresu w postaci `bus:device.function`. Na przykład narzędzie `lspci`, obecne w wielu dystrybucjach Linuksa, korzysta z tej konwencji:

```console
[root@host]# lspci
00:00.0 Host bridge: Intel Corporation...
```

## Przestrzeń konfiguracyjna urządzeń

Każde logiczne urządzenie PCIe musi posiadać ustandaryzowany zestaw rejestrów, zwany przestrzenią konfiguracyjną (_ang. configuration space_). Przestrzeń konfiguracyjna ma rozmiar 4kB i zawiera m.in. informacje o producencie i modelu, czy rejestry pozwalające na konfigurację urządzenia. Pełny opis jej struktury zaczyna się na stronie 349 dokumentu ["PCI Express Base Specification 1.1"][3]

Istnieją dwa mechanizmy dostępu do przestrzeni konfiguracyjnej urządzenia: klasyczny, pochodzący jeszcze z czasów magistrali PCI (bez "e"), wykorzystujący instrukcje asemblerowe `in` i `out`, oraz ulepszony, który pojawił się wraz z nadejściem standardu PCI-Express.

Mechanizm "ulepszony", na którym skupię się w dalszej części tego tekstu, wykorzystuje odwzorowanie rejestrów urządzenia w pamięci (_ang. memory-mapped configuration space_). Mianowicie, istnieje pewien ciągły obszar fizycznej przestrzeni adresowej, w którym odwzorowane są przestrzenie konfiguracyjne kolejnych urządzeń - przestrzeń konfiguracyjna urządzenia o adresie `bus:device.function` jest przesunięta o `(bus << 8 + device << 3 + function) * 4kB` względem początku wspomnianego obszaru. 

No dobrze, a gdzie znajduje się ten początek? W dokumencie ["PCI Firmware Specification"][1] można odnaleźć informację, że adres bazowy przestrzeni konfiguracyjnej PCIe jest przechowywany w tabeli ACPI "MCFG", pod offsetem 44B. Ponieważ Linux udostępnia tabele ACPI poprzez system plików `sysfs`, możemy łatwo pobrać tę wartość:

```console
[root@host]# dd if=/sys/firmware/acpi/tables/MCFG bs=1 skip=44 count=8\
  2> /dev/null| hexdump -e '1/8 "%x\n"'
dc000000
```

## Eksperyment

Spróbujmy teraz sprawdzić tę wiedzę w praktyce i stworzyć prosty skrypt basha, który znajdzie wszystkie urządzenia PCIe, używając do tego tylko danych z surowego obrazu pamięci reprezentowanego przez `/dev/mem`.

> Nowsze wersje Linuksa są domyślnie kompilowane z opcją kernela `CONFIG_STRICT_DEVMEM=y`, która znacznie ogranicza dostęp do urządzenia `/dev/mem`, dlatego ten eksperyment wymaga przekompilowania jądra lub użycia starszej dystrybucji. Ja użyłem Ubuntu 8.04 na wirtualnej maszynie z 4GB pamięci RAM. 

```bash
#!/bin/bash
# list_pci.sh

# Sprawdź wszystkie numery urządzeń i funkcji dla pierwszych 10 szyn
# (przeszukanie 256 szyn tym sposobem zajęłoby zbyt dużo czasu...)
for bus in {0..9}; do
for dev in {0..31}; do
for fun in {0..7}; do
  # Oblicz adres przestrzeni konfiguracyjnej urządzenia
  cfg_index=$(((bus * 256) + (dev * 8) + fun))
  cfg_addr=$((0xdc000000 + cfg_index * 4096))

  # Zdekoduj identyfikator producenta i modelu urządzenia (pierwsze 4B
  # przestrzeni konfiguracyjnej) i zapisz w formacie: "vendor:model"
  dev_model=$(dd if=/dev/mem bs=1 count=4 skip=${cfg_addr} \
    2>/dev/null | hexdump -e '1/2 "%04x:" 1/2 "%04x"')
  
  # "ffff:ffff" (same jedynki w systemie dwójkowym) oznacza 
  # brak urządzenia o danym adresie
  [[ $dev_model == "ffff:ffff" ]] && continue
  printf "%02x:%02x.%02x (%s)\n" $bus $dev $fun "$dev_model"
done
done
done
```
Uruchomienie skryptu daje wyniki zgodne z odpowiedzią komendy `lspci`:
```console
[root@host]# ./list_pci.sh
00:02.00 (15ad:0405)
00:03.00 (8086:100e)
00:04.00 (80ee:cafe)
00:05.00 (8086:2415)
00:07.00 (8086:7113)
00:1f.00 (8086:27b9)
00:1f.01 (8086:7111)
00:1f.02 (8086:2829)
00:1f.04 (106b:003f)
```

## Zakończenie

Polecam [tę][2] serię filmów, aby dowiedzieć się nieco więcej na temat urządzeń PCI i architektury komputerów.

[1]: http://read.pudn.com/downloads211/doc/comm/994029/pcifw_r3_0_updated.pdf "PCI Firmware Specification"
[2]: https://www.youtube.com/watch?v=4hr1aXf2ark&list=PLBTQvUDSl81dTG_5Uk2mycxZihfeAYTRm "System Architecture for BIOS/System Software Developers"
[3]: http://read.pudn.com/downloads161/doc/729268/PCI_Express_Base_11.pdf "PCI Base Specification"
