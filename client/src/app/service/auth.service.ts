import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {tap} from 'rxjs/operators';
import {Observable, Observer, throwError} from 'rxjs';
import {BaseService} from './base.service';
import {CookieService} from 'ngx-cookie-service';


@Injectable({
  providedIn: 'root'
})
export class AuthService extends BaseService {

  $authenticationState: Observable<boolean>;
  private authTokenCookieName = 'auth_token';
  private accessToken: string = null;
  private observers: Observer<boolean>[];

  constructor(private http: HttpClient,
              private cookieService: CookieService) {
    super();
    this.observers = [];
    this.$authenticationState = new Observable((observer: Observer<boolean>) => {
      this.observers.push(observer);
    });

    if (cookieService.check(this.authTokenCookieName)) {
      this.accessToken = cookieService.get(this.authTokenCookieName);
    }
  }

  public isAuthenticated(): boolean {
    return this.accessToken != null;
  }

  public backendAuthCheck(): Observable<string> {
    if (this.isAuthenticated()) {
      return this.http.post<string>(
        this.baseurl + '/admin/hi',
        {
          headers: {
            Authorization: this.getAccessToken()
          },
          responseType: 'text' as 'json'
        });
    } else {
      return throwError('No token');
    }
  }

  public login(u: string, p: string): Observable<string> {
    return this.http.post<string>(
      this.baseurl + '/auth/login',
      'username=' + u + '&password=' + p,
      {
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        responseType: 'text' as 'json'
      })
      .pipe(
        tap(res => {
          this.accessToken = res as string;
          if (this.isAuthenticated()) {
            this.cookieService.set(this.authTokenCookieName, this.accessToken, 5, '/');
            this.emitAuthenticationState(true);
          }
        })
      );
  }

  public logout() {
    this.accessToken = null;
    this.cookieService.delete(this.authTokenCookieName, '/');
    this.emitAuthenticationState(false);
  }

  public getAccessToken(): string {
    return this.accessToken;
  }

  private emitAuthenticationState(state: boolean) {
    this.observers.forEach(observer => observer.next(state));
  }
}
