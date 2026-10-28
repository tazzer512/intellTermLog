# Sourced into each Log Console shell. Tab in the command box calls __lc_complete with the text
# before the caret and reads the candidates (one per line) from the file it is redirected to.

__lc_files() {
  local f
  compgen -f -- "$1" | while IFS= read -r f; do
    if [[ -d ${f/#\~/$HOME} ]]; then printf '%s/\n' "$f"; else printf '%s\n' "$f"; fi
  done
}

# Trims trailing blanks (git adds them) and appends / to directories, like readline's -o filenames.
__lc_mark_dirs() {
  local f
  while IFS= read -r f; do
    f=${f%"${f##*[![:space:]]}"}
    if [[ $f != */ && -d ${f/#\~/$HOME} ]]; then printf '%s/\n' "$f"; else printf '%s\n' "$f"; fi
  done
}

__lc_complete() {
  local line=$1 cur spec func
  local -a words
  local split=${line//[;|&]/ ; }
  read -ra words <<< "$split"
  [[ -z $split || $split =~ [[:space:]]$ ]] && words+=("")
  local cword=$(( ${#words[@]} - 1 ))
  cur=${words[cword]}

  if [[ $cur == \$* ]]; then
    compgen -v -- "${cur#\$}" | sed 's/^/$/'
    return
  fi
  # A word at the start, or after a separator / command wrapper, is a command name.
  if (( cword == 0 )) || [[ ${words[cword-1]} =~ ([\;\|\&]|^sudo|^time|^exec|^nohup|^xargs|^watch)$ ]]; then
    if [[ $cur == */* || $cur == .* || $cur == \~* ]]; then __lc_files "$cur"; else compgen -abck -A function -- "$cur" | sort -u; fi
    return
  fi

  # Programmable completion (git, mvn, docker, ... via bash-completion), loaded on demand.
  spec=$(complete -p -- "${words[0]}" 2>/dev/null)
  if [[ -z $spec ]] && declare -F _completion_loader >/dev/null; then
    _completion_loader "${words[0]}" >/dev/null 2>&1
    spec=$(complete -p -- "${words[0]}" 2>/dev/null)
  fi
  if [[ $spec =~ \ -F\ ([^ ]+) ]]; then
    func=${BASH_REMATCH[1]}
    local COMP_LINE=$line COMP_POINT=${#line} COMP_CWORD=$cword COMP_TYPE=9 COMP_KEY=9
    local -a COMP_WORDS=("${words[@]}") COMPREPLY=()
    "$func" "${words[0]}" "$cur" "${words[cword-1]}" >/dev/null 2>&1
    if (( ${#COMPREPLY[@]} )); then
      printf '%s\n' "${COMPREPLY[@]}" | __lc_mark_dirs
      return
    fi
  fi
  __lc_files "$cur"
}
